package com.csust.eyediagnosis.poc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * 协调 PoC 夹具的连接入口。
 *
 * <p>凭据只在这里读一次：默认仍是原套件的 {@code poc/poc}（本地一次性库），
 * CI 用 {@code COORDINATION_POC_USER} / {@code COORDINATION_POC_PASSWORD} 覆盖。
 * 子进程由 {@link ProcessBuilder} 启动，默认继承父进程环境，所以不必把口令拼进命令行
 * ——那会让它出现在进程列表里。
 */
final class CoordinationDb {

    private CoordinationDb() {
    }

    static String user() {
        String value = System.getenv("COORDINATION_POC_USER");
        return value == null || value.isBlank() ? "poc" : value;
    }

    static String password() {
        String value = System.getenv("COORDINATION_POC_PASSWORD");
        return value == null || value.isBlank() ? "poc" : value;
    }

    static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, user(), password());
    }

    static Connection connect(String url, int connectTimeout, int socketTimeout) throws SQLException {
        var options = new Properties();
        options.setProperty("user", user());
        options.setProperty("password", password());
        options.setProperty("connectTimeout", Integer.toString(connectTimeout));
        options.setProperty("socketTimeout", Integer.toString(socketTimeout));
        return DriverManager.getConnection(url, options);
    }
}
