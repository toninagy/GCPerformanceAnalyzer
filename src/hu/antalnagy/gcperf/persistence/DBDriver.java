package hu.antalnagy.gcperf.persistence;

import hu.antalnagy.gcperf.GCType;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DBDriver implements AutoCloseable {

    private static final String JDBC_DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final String DB_URL = "jdbc:mysql://localhost/statistics";
    private static final String TABLE_NAME = "stats";
    private Connection connection;
    private Statement statement;

    private static final Logger LOGGER = Logger.getLogger(DBDriver.class.getSimpleName());

    public static Logger getLOGGER() {
        return LOGGER;
    }

    static {
        try {
            Class.forName(JDBC_DRIVER);
        } catch (ClassNotFoundException ex) {
            ex.printStackTrace();
        }
    }

    public DBDriver() {
        try {
            connection = DriverManager.getConnection("jdbc:mysql://localhost/","root","root");
            statement = connection.createStatement();
            statement.addBatch("CREATE DATABASE IF NOT EXISTS statistics;");
            statement.addBatch("USE statistics;");
            statement.addBatch(
                    """
                            CREATE TABLE IF NOT EXISTS stats (
                                stat_id INT AUTO_INCREMENT PRIMARY KEY,
                                file_name VARCHAR(255),
                                ranking_serial TINYINT,
                                ranking_parallel TINYINT,
                                ranking_g1 TINYINT,
                                ranking_zgc TINYINT,
                                ranking_shenandoah TINYINT,
                                date_created TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                            );""");
            statement.executeBatch();
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Connection establishment or database creation attempt failed");
            ex.printStackTrace();
        }
    }

    public void insertRow(Timestamp timeStamp, String fileName, List<GCType> leaderBoard) {
        try {
            if(connection.isClosed()) {
                LOGGER.log(Level.WARNING, "Update attempt with closed connection");
                throw new IllegalStateException("Connection is closed");
            }
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Connection closed query resulted in SQLException");
            ex.printStackTrace();
        }
        Map<GCType, Integer> rankings = new HashMap<>();
        for(GCType gcType : GCType.values()) {
            rankings.put(gcType, null);
        }
        for(int i = 0; i < leaderBoard.size(); i++) {
            rankings.replace(leaderBoard.get(i), i+1);
        }
        String updateString = constructSqlUpdateString(timeStamp, fileName, rankings.get(GCType.SERIAL),
                rankings.get(GCType.PARALLEL), rankings.get(GCType.G1), rankings.get(GCType.ZGC), rankings.get(GCType.SHENANDOAH));
        try {
            statement.executeUpdate(updateString);
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Update attempt failed");
            ex.printStackTrace();
        }
    }

    private String constructSqlUpdateString(Timestamp timeStamp, String fileName, Integer serial, Integer parallel,
                                      Integer g1, Integer zgc, Integer shenandoah) {
        return "INSERT INTO " + TABLE_NAME + " (file_name, ranking_serial, ranking_parallel, ranking_g1, ranking_zgc, " +
                "ranking_shenandoah, date_created) VALUES (\"" + fileName + "\", " + serial + ", " + parallel + ", " +
                g1 + ", " + zgc + ", " + shenandoah + ", \"" + timeStamp + "\");";

    }

    public Map<Integer, List<String>> queryRows() {
        ResultSet resultSet = null;
        Map<Integer, List<String>> resultMap = new HashMap<>();
        try {
            if(connection.isClosed()) {
                LOGGER.log(Level.WARNING, "Query attempt with closed connection");
                throw new IllegalStateException("Connection is closed");
            }
            resultSet = statement.executeQuery(constructSqlQueryString());
            while(resultSet.next()) {
                int id = resultSet.getInt("stat_id");
                String fileName = resultSet.getString("file_name");
                int serialRanking = resultSet.getInt("ranking_serial");
                int parallelRanking = resultSet.getInt("ranking_parallel");
                int g1Ranking = resultSet.getInt("ranking_g1");
                int zgcRanking = resultSet.getInt("ranking_zgc");
                int shenandoahRanking = resultSet.getInt("ranking_shenandoah");
                Timestamp dateCreated = resultSet.getTimestamp("date_created");
                List<String> strings = new ArrayList<>();
                Collections.addAll(strings, fileName, String.valueOf(serialRanking),
                        String.valueOf(parallelRanking), String.valueOf(g1Ranking), String.valueOf(zgcRanking),
                        String.valueOf(shenandoahRanking), String.valueOf(dateCreated));
                resultMap.put(id, strings);
            }
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Query resulted in SQLException");
            ex.printStackTrace();
        } finally {
            try {
                if(resultSet != null) {
                    resultSet.close();
                }
            } catch (SQLException ex) {
                LOGGER.log(Level.SEVERE, "Closing ResultSet resource failed");
                ex.printStackTrace();
            }
        }
        return resultMap;
    }

    private String constructSqlQueryString() {
        return "SELECT * FROM " + TABLE_NAME + ";";
    }

    public void createConnectionAndStatement() {
        try {
            if(!connection.isClosed()) {
                LOGGER.log(Level.WARNING, "Connection already established");
                return;
            }
            this.connection = DriverManager.getConnection(DB_URL,"root","root");
            this.statement = this.connection.createStatement();
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "Connection establishment or statement creation attempt failed");
            ex.printStackTrace();
        }
    }

    public static Map<Integer, List<String>> getDatabaseRows() {
        try(DBDriver dbDriver = new DBDriver()) {
            return dbDriver.queryRows();
        }
    }

    @Override
    public void close() {
        try {
            if(statement != null) {
                statement.close();
            }
            if(connection != null) {
                connection.close();
            }
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "Connection or statement close attempt failed");
            ex.printStackTrace();
        }
    }
}
