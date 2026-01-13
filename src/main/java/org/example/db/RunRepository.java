package org.example.db;

import org.example.model.RunMode;

import java.sql.*;

public final class RunRepository {

    public long createRun(long datasetId, RunMode mode, int k, int threads, int maxIter, double eps) {
        String sql = """
            INSERT INTO runs(dataset_id, mode, k, threads, max_iter, eps)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setLong(1, datasetId);
            ps.setString(2, mode.name());
            ps.setInt(3, k);
            ps.setInt(4, threads);
            ps.setInt(5, maxIter);
            ps.setDouble(6, eps);

            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No key for run");
                return keys.getLong(1);
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to create run", e);
        }
    }

    public void finishRun(long runId, String stopReason) {
        String sql = """
            UPDATE runs
            SET status = 'FINISHED',
                stop_reason = ?,
                finished_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;

        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setString(1, stopReason);
            ps.setLong(2, runId);
            ps.executeUpdate();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to finish run id=" + runId, e);
        }
    }
}
