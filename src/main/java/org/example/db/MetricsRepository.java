package org.example.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class MetricsRepository {

    public void insertIterMetrics(long runId, int iter, double sse,
                                  double assignMs, double updateMs, double totalMs) {
        String sql = """
            INSERT INTO iter_metrics(run_id, iter, sse, assign_ms, update_ms, total_ms)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, runId);
            ps.setInt(2, iter);
            ps.setDouble(3, sse);
            ps.setDouble(4, assignMs);
            ps.setDouble(5, updateMs);
            ps.setDouble(6, totalMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert iter_metrics", e);
        }
    }

    public void insertRunMetrics(long runId, long totalMs, int iterations, double finalSse,
                                 double avgIterMs, double avgAssignMs, double avgUpdateMs) {
        String sql = """
            INSERT INTO run_metrics(run_id, total_ms, iterations, final_sse, avg_iter_ms, avg_assign_ms, avg_update_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, runId);
            ps.setLong(2, totalMs);
            ps.setInt(3, iterations);
            ps.setDouble(4, finalSse);
            ps.setDouble(5, avgIterMs);
            ps.setDouble(6, avgAssignMs);
            ps.setDouble(7, avgUpdateMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert run_metrics", e);
        }
    }
    public void saveClusterMetrics(long runId, int[] size, double[] clusterSse, double[] avgDist, double[] maxDist) {
        String sql = """
        INSERT INTO cluster_metrics(run_id, cluster_id, size, cluster_sse, avg_dist, max_dist)
        VALUES (?, ?, ?, ?, ?, ?)
        """;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            c.setAutoCommit(false);
            for (int k = 0; k < size.length; k++) {
                ps.setLong(1, runId);
                ps.setInt(2, k);
                ps.setInt(3, size[k]);
                ps.setDouble(4, clusterSse[k]);
                ps.setDouble(5, avgDist[k]);
                ps.setDouble(6, maxDist[k]);
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert cluster_metrics", e);
        }
    }

}
