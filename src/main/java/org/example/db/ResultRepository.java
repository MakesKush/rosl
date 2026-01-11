package org.example.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class ResultRepository {

    public void saveAssignments(long runId, int[] assignment) {
        String sql = "INSERT INTO assignments(run_id, point_idx, cluster_id) VALUES (?, ?, ?)";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            c.setAutoCommit(false);
            for (int i = 0; i < assignment.length; i++) {
                ps.setLong(1, runId);
                ps.setInt(2, i);
                ps.setInt(3, assignment[i]);
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to save assignments runId=" + runId, e);
        }
    }

    public void saveCentroids(long runId, double[][] c) {
        String sql = """
            INSERT INTO centroids(run_id, cluster_id, sports, games, music, movies, memes, likes, comments)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);
            for (int k = 0; k < c.length; k++) {
                ps.setLong(1, runId);
                ps.setInt(2, k);

                ps.setDouble(3, c[k][0]);
                ps.setDouble(4, c[k][1]);
                ps.setDouble(5, c[k][2]);
                ps.setDouble(6, c[k][3]);
                ps.setDouble(7, c[k][4]);
                ps.setDouble(8, c[k][5]);
                ps.setDouble(9, c[k][6]);

                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to save centroids runId=" + runId, e);
        }
    }
}
