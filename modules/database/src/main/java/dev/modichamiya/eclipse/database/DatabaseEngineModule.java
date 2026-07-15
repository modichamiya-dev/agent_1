package dev.modichamiya.eclipse.database;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.modichamiya.eclipse.api.EclipseApi;
import dev.modichamiya.eclipse.core.CoreRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class DatabaseEngineModule implements CoreRuntime.EngineModule {
    private HikariDataSource dataSource;
    private ExecutorService ioExecutor;

    @Override
    public String id() {
        return "database";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("core", "config");
    }

    @Override
    public void onEnable(CoreRuntime.ModuleContext context) {
        EclipseApi.ConfigService configService = context.services().require(EclipseApi.ConfigService.class);
        EclipseApi.ConfigSection databaseConfig = configService.config("database");

        Path dataDirectory = context.plugin().getDataFolder().toPath();
        Path databasePath = dataDirectory.resolve(databaseConfig.string("file-name", "eclipse.db"));
        try {
            Files.createDirectories(dataDirectory);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create plugin data directory", exception);
        }

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + databasePath.toAbsolutePath());
        hikariConfig.setMaximumPoolSize(databaseConfig.intValue("pool-size", 8));
        hikariConfig.setConnectionTimeout(databaseConfig.longValue("connection-timeout-ms", 10000L));
        hikariConfig.setPoolName("EclipseSQLitePool");
        hikariConfig.setConnectionInitSql("PRAGMA foreign_keys = ON");

        this.dataSource = new HikariDataSource(hikariConfig);
        this.ioExecutor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() / 2));

        DatabaseServiceImpl service = new DatabaseServiceImpl(dataSource, ioExecutor);
        service.runAsync(() -> runMigrations(service)).join();
        context.services().register(EclipseApi.DatabaseService.class, service);
        context.logger(id()).info("SQLite ready at " + databasePath.toAbsolutePath());
    }

    @Override
    public void onDisable(CoreRuntime.ModuleContext context) {
        if (ioExecutor != null) {
            ioExecutor.shutdown();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private void runMigrations(DatabaseServiceImpl databaseService) {
        try (Connection connection = databaseService.dataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");

            int currentVersion = 0;
            try (ResultSet resultSet = statement.executeQuery("SELECT version FROM schema_version LIMIT 1")) {
                if (resultSet.next()) {
                    currentVersion = resultSet.getInt("version");
                }
            }

            if (currentVersion < 1) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS players (" +
                        "uuid TEXT PRIMARY KEY," +
                        "last_name TEXT NOT NULL," +
                        "created_at TEXT NOT NULL," +
                        "last_seen_at TEXT NOT NULL," +
                        "progression_json TEXT NOT NULL" +
                        ")");
                statement.executeUpdate("DELETE FROM schema_version");
                statement.executeUpdate("INSERT INTO schema_version(version) VALUES (1)");
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to apply database migrations", exception);
        }
    }
}

final class DatabaseServiceImpl implements EclipseApi.DatabaseService {
    private final HikariDataSource dataSource;
    private final ExecutorService ioExecutor;

    DatabaseServiceImpl(HikariDataSource dataSource, ExecutorService ioExecutor) {
        this.dataSource = dataSource;
        this.ioExecutor = ioExecutor;
    }

    @Override
    public HikariDataSource dataSource() {
        return dataSource;
    }

    @Override
    public ExecutorService ioExecutor() {
        return ioExecutor;
    }

    @Override
    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, ioExecutor);
    }

    @Override
    public CompletableFuture<Void> runAsync(Runnable runnable) {
        return CompletableFuture.runAsync(runnable, ioExecutor);
    }
}

interface Repository<T, ID> {
    CompletableFuture<T> findById(ID id);

    CompletableFuture<Void> save(T entity);
}

abstract class BaseSqlRepository<T, ID> implements Repository<T, ID> {
    protected final EclipseApi.DatabaseService databaseService;
    protected final Gson gson = new GsonBuilder().create();

    protected BaseSqlRepository(EclipseApi.DatabaseService databaseService) {
        this.databaseService = Objects.requireNonNull(databaseService);
    }
}
