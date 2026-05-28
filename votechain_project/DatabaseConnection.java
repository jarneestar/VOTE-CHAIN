import java.sql.Connection;
import java.sql.DriverManager;

public class DatabaseConnection {

    private static final String URL =
        "jdbc:mysql://zephyr.proxy.rlwy.net:3306/railway";

    private static final String USER = "root";
    private static final String PASSWORD =
        "kyUmFcFTWFXtiwUXJGOVrCxDOGPGCEMj";

    public static Connection getConnection() {
        try {
            return DriverManager.getConnection(URL, USER, PASSWORD);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
