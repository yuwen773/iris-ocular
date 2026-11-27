/**
 * Iris · PostgreSQL migration runner (single-file, no Flyway CLI install needed).
 *
 * Runs the real Flyway line that ships in src/main/resources/db/postgresql, as the
 * migrator role (DDL), and validates afterwards. The application also runs Flyway
 * on startup; this tool exists for deploys and drills where the app is not up yet,
 * and for target databases the app should not touch (CI, migration drills).
 *
 * Usage (from the repository root, after `mvn -q compile`):
 *
 *   CP=$(mvn -o -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)
 *   java -cp "target/classes:$CP" scripts/postgresql/FlywayMigrate.java \
 *        jdbc:postgresql://127.0.0.1:5432/iris?currentSchema=diagnosis
 *
 * Credentials come from IRIS_FLYWAY_USER / IRIS_FLYWAY_PASSWORD (default
 * iris_migrator / empty). Locations default to classpath:db/postgresql; pass a
 * third argument to override (e.g. a filesystem path during review).
 */
import org.flywaydb.core.Flyway;

public class FlywayMigrate {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: FlywayMigrate <jdbc-url> [locations]");
            System.exit(3);
        }
        String url = args[0];
        String locations = args.length > 1 ? args[1] : "classpath:db/postgresql";
        String user = System.getenv().getOrDefault("IRIS_FLYWAY_USER", "iris_migrator");
        String password = System.getenv().getOrDefault("IRIS_FLYWAY_PASSWORD", "");

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .schemas("diagnosis")
                .defaultSchema("diagnosis")
                .createSchemas(true)
                .locations(locations)
                .validateOnMigrate(true)
                .load();

        var result = flyway.migrate();
        System.out.println("MIGRATED=" + result.migrationsExecuted
                + " VERSION=" + flyway.info().current().getVersion());
        flyway.validate();
        System.out.println("VALIDATE=OK");
    }
}
