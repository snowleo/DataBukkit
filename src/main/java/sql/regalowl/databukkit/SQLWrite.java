package regalowl.databukkit;


import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.bukkit.scheduler.BukkitTask;

public class SQLWrite {

	private DataBukkit dab;
	private ConcurrentHashMap<Long, WriteStatement> buffer = new ConcurrentHashMap<Long, WriteStatement>();
	private BukkitTask writeTask;
	private AtomicBoolean writeActive = new AtomicBoolean();
	private AtomicLong bufferCounter = new AtomicLong();
	private AtomicLong processNext = new AtomicLong();
	private AtomicBoolean logWriteErrors = new AtomicBoolean();;
    private Queue<DatabaseConnection> connections = new LinkedList<DatabaseConnection>();
    private Queue<DatabaseConnection> activeConnections = new LinkedList<DatabaseConnection>();
    private Lock connectionLock = new ReentrantLock();
    private Condition connectionAvailable = connectionLock.newCondition();
	private AtomicBoolean shutDown = new AtomicBoolean();
    private final long writeDelay = 20L;
    private AtomicBoolean logSQL = new AtomicBoolean();

	public SQLWrite(DataBukkit dabu) {
		logWriteErrors.set(true);
		this.dab = dabu;

		DatabaseConnection dc = null;
		if (dab.useMySQL()) {
			dc = new MySQLConnection(dab, false, false);
		} else {
			dc = new SQLiteConnection(dab, false, false);
		}
		returnConnection(dc);
		logSQL.set(false);
		bufferCounter.set(0);
		processNext.set(0);
		writeActive.set(false);
		shutDown.set(false);
	}
	
	
	
	
	public void returnConnection(DatabaseConnection connection) {
		connectionLock.lock();
		try {
			activeConnections.remove(connection);
			connections.add(connection);
			connectionAvailable.signal();
		} finally {
			connectionLock.unlock();
		}
	}
	private DatabaseConnection getDatabaseConnection() {
		connectionLock.lock();
		try {
			while (connections.isEmpty()) {
				try {
					connectionAvailable.await();
				} catch (InterruptedException e) {
					dab.writeError(e, null);
				}
			}
			DatabaseConnection connect = connections.remove();
			activeConnections.add(connect);
			return connect;
		} finally {
			connectionLock.unlock();
		}
	}
	
	public synchronized void executeSynchronously(String statement) {
		DatabaseConnection database = getDatabaseConnection();
		ArrayList<WriteStatement> statements = new ArrayList<WriteStatement>();
		statements.add(new WriteStatement(statement, dab));
		database.write(statements, logWriteErrors.get());
	}
	public synchronized void convertExecuteSynchronously(String statement) {
		executeSynchronously(convertSQL(statement));
	}

	public synchronized void addWriteStatementsToQueue(List<WriteStatement> statements) {
		for (WriteStatement statement : statements) {
			if (statement == null) {continue;}
			buffer.put(bufferCounter.getAndIncrement(), statement);
		}
		startWriteTask();
	}
	public synchronized void addToQueue(List<String> statements) {
		for (String statement : statements) {
			if (statement == null) {continue;}
			buffer.put(bufferCounter.getAndIncrement(), new WriteStatement(statement, dab));
		}
		startWriteTask();
	}
	public synchronized void convertAddToQueue(String statement) {
		addToQueue(convertSQL(statement));
	}
	public synchronized void addToQueue(String statement) {
		if (statement == null) {return;}
		buffer.put(bufferCounter.getAndIncrement(), new WriteStatement(statement, dab));
		startWriteTask();
	}
	public synchronized void addToQueue(WriteStatement statement) {
		if (statement == null) {return;}
		buffer.put(bufferCounter.getAndIncrement(), statement);
		startWriteTask();
	}
	public synchronized void addToQueue(String statement, ArrayList<Object> parameters) {
		if (statement == null) {return;}
		WriteStatement ws = new WriteStatement(statement, dab);
		for (Object param:parameters) {
			ws.addParameter(param);
		}
		buffer.put(bufferCounter.getAndIncrement(), ws);
		startWriteTask();
	}

	private synchronized void startWriteTask() {
		if (writeActive.get() || buffer.isEmpty() || shutDown.get()) {return;}
		writeActive.set(true);
		writeTask = dab.getPlugin().getServer().getScheduler().runTaskLaterAsynchronously(dab.getPlugin(), new Runnable() {
			public void run() {
				DatabaseConnection database = getDatabaseConnection();
				ArrayList<WriteStatement> writeArray = new ArrayList<WriteStatement>();
				while (buffer.size() > 0) {
					writeArray.add(buffer.get(processNext.get()));
					buffer.remove(processNext.getAndIncrement());
				}
				database.write(writeArray, logWriteErrors.get());
				if (shutDown.get()) {return;}
				writeActive.set(false);
				if (!buffer.isEmpty()) {startWriteTask();}
			}
		}, writeDelay);
	}

	public int getBufferSize() {
		return buffer.size();
	}

	public synchronized int getActiveThreads() {
		return 1 - connections.size();
	}
/*
	public synchronized ArrayList<String> getBuffer() {
		ArrayList<String> abuffer = new ArrayList<String>();
		for (String item : buffer.values()) {
			abuffer.add(item);
		}
		return abuffer;
	}
*/
	public synchronized void shutDown() {
		shutDown.set(true);
		if (writeTask != null) {writeTask.cancel();}
		writeActive.set(true);
		while (!connections.isEmpty()){
			connections.remove().closeConnection();
		}
		while (!activeConnections.isEmpty()){
			activeConnections.remove().closeConnection();
		}
		saveBuffer();
	}
	private synchronized void saveBuffer() {
		if (buffer.size() > 0) {
			dab.getLogger().info("[DataBukkit["+dab.getPlugin().getName()+"]]Saving the remaining SQL queue: ["+buffer.size()+" statements].  Please wait.");
			DatabaseConnection database = null;
			if (dab.useMySQL()) {
				database = new MySQLConnection(dab, false, true);
			} else {
				database = new SQLiteConnection(dab, false, true);
			}
			ArrayList<WriteStatement> writeArray = new ArrayList<WriteStatement>();
			while (buffer.size() > 0) {
				writeArray.add(buffer.get(processNext.get()));
				buffer.remove(processNext.getAndIncrement());
			}
			database.write(writeArray, logWriteErrors.get());
			buffer.clear();
			dab.getLogger().info("[DataBukkit["+dab.getPlugin().getName()+"]]SQL queue save complete.");
		}
	}
	public AtomicBoolean shutdownStatus() {
		return shutDown;
	}
	
	
	
	public void createSqlTable(String name, ArrayList<String> fields) {
		String statement = "CREATE TABLE IF NOT EXISTS " + name + " (";
		for (int i=0; i < fields.size(); i++) {
			String field = convertSQL(fields.get(i));
			if (i < (fields.size() - 1)) {
				statement += field + ", ";
			} else {
				statement += field + ")";
			}
		}
		addToQueue(statement);
	}
	
	public void performInsert(String table, HashMap<String, String> values) {
		String statement = "INSERT INTO " + table + " (";
		for (String field:values.keySet()) {
			statement += field + ", ";
		}
		statement = statement.substring(0, statement.length() - 2);
		statement += ") VALUES (";
		for (int i=0; i<values.size(); i++) {
			statement +=  "?,";
		}
		statement = statement.substring(0, statement.length() - 1);
		statement += ")";
		WriteStatement ws = new WriteStatement(statement, dab);
		for (String value:values.values()) {
			ws.addParameter(convertSQL(value));
		}
		addToQueue(ws);
	}

	public void performUpdate(String table, HashMap<String, String> values, HashMap<String, String> conditions) {
		String statement = "UPDATE " + table + " SET ";
		Iterator<String> it = values.keySet().iterator();
		while (it.hasNext()) {
			statement += it.next() + " = ?, ";
		}
		statement = statement.substring(0, statement.length() - 2);
		statement += " WHERE ";
		it = conditions.keySet().iterator();
		while (it.hasNext()) {
			String field = it.next();
			statement += field + " = ? AND ";
		}
		statement = statement.substring(0, statement.length() - 5);
		WriteStatement ws = new WriteStatement(statement, dab);
		it = values.keySet().iterator();
		while (it.hasNext()) {
			String field = it.next();
			ws.addParameter(values.get(field));
		}
		it = conditions.keySet().iterator();
		while (it.hasNext()) {
			String field = it.next();
			ws.addParameter(conditions.get(field));
		}
		addToQueue(ws);
	}
	
	public void performDelete(String table, HashMap<String, String> conditions) {
		String statement = "DELETE FROM " + table + " WHERE ";
		Iterator<String> it = conditions.keySet().iterator();
		while (it.hasNext()) {
			String field = it.next();
			statement += field + " = ? AND ";
		}
		statement = statement.substring(0, statement.length() - 5);
		WriteStatement ws = new WriteStatement(statement, dab);
		
		it = conditions.keySet().iterator();
		while (it.hasNext()) {
			String field = it.next();
			ws.addParameter(conditions.get(field));
		}
		addToQueue(ws);
	}

	public String convertSQL(String statement) {
		if (statement == null) {return statement;}
		if (dab.useMySQL()) {
			statement = statement.replace("datetime('NOW', 'localtime')", "NOW()");
			statement = statement.replace("AUTOINCREMENT", "AUTO_INCREMENT");
			statement = statement.replace("autoincrement", "auto_increment");
		} else {
			statement = statement.replace("NOW()", "datetime('NOW', 'localtime')");
			statement = statement.replace("AUTO_INCREMENT", "AUTOINCREMENT");
			statement = statement.replace("auto_increment", "autoincrement");
		}
		return statement;
	}
	
	public void setErrorLogging(boolean state) {
		logWriteErrors.set(state);
	}
	
	public boolean writeActive() {
		return writeActive.get();
	}
	
	public void setLogSQL(boolean state) {
		logSQL.set(state);
	}
	
	public boolean logSQL() {
		return logSQL.get();
	}
	
	public synchronized void logSQL(WriteStatement statement) {
		ErrorWriter ew = new ErrorWriter(dab.getPluginFolderPath() + "SQL.log", dab);
		ArrayList<Object> parameters = statement.getParameters();
		if (parameters != null && parameters.size() > 0) {
			String paramList = "[";
			for (Object p:parameters) {
				if (p == null) {p = "";}
				paramList += "["+p.toString() + "], ";
			}
			paramList = paramList.substring(0, paramList.length() - 2) + "]";
			ew.writeError(null, statement.getStatement().replace("%n", "[new line]") + "%nParameters: "+paramList.replace("%n", "[new line]"), true);
		} else {
			ew.writeError(null, statement.getStatement().replace("%n", "[new line]"), true);
		}
		
	}
	/**
	 * This method will call the method of your choice in the class of your choice (the class of the object) after the current write operation is complete.
	 * 
	 * @param object The object that holds the method you want to call.
	 * @param method The name of the method you want to call.
	 */
	public void afterWrite(Object object, String method) {
		new AfterWrite(object, method);
	}
	private class AfterWrite {
		private String m;
		private Object o;
		private BukkitTask afterWriteTask;
		AfterWrite(Object object, String method) {
			this.o = object;
			this.m = method;
			afterWriteTask = dab.getPlugin().getServer().getScheduler().runTaskTimer(dab.getPlugin(), new Runnable() {
				public void run() {
					if (!writeActive.get() && buffer.size() == 0) {
						try {
							Method meth = o.getClass().getMethod(m);
							meth.invoke(o.getClass().newInstance());
						} catch (Exception e) {
							dab.writeError(e, null);
						} finally {
							afterWriteTask.cancel();
						}
						return;
					}
				}
			}, 1L, 1L);
		}
	}

}
