package org.example.db;

import org.example.model.DatasetInfo;
import org.example.model.Feature;
import org.example.model.PointVector;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class DatasetRepository {

    public List<DatasetInfo> listDatasets() {
        String sql = "SELECT id, name, n, d, created_at FROM datasets ORDER BY created_at DESC";
        List<DatasetInfo> out = new ArrayList<>();

        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                long id = rs.getLong("id");
                String name = rs.getString("name");
                int n = rs.getInt("n");
                int d = rs.getInt("d");
                Timestamp ts = rs.getTimestamp("created_at");
                LocalDateTime createdAt = ts != null ? ts.toLocalDateTime() : null;

                out.add(new DatasetInfo(id, name, n, d, createdAt));
            }
            return out;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to list datasets", e);
        }
    }
    public boolean deleteDataset(long datasetId) {
        String sql = "DELETE FROM DATASETS WHERE ID = ?";

        try (var c = Database.getConnection()) { // если у тебя метод называется иначе — подставь свой
            c.setAutoCommit(false);
            int affected;

            try (var ps = c.prepareStatement(sql)) {
                ps.setLong(1, datasetId);
                affected = ps.executeUpdate();
            }

            c.commit();
            return affected > 0;
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete dataset id=" + datasetId, e);
        }
    }


    public long createDataset(String name, int n, long seed, double noiseSigma, List<PointVector> points) {
        if (points.size() != n) {
            throw new IllegalArgumentException("points.size != n");
        }

        String insertDataset = "INSERT INTO datasets(name, n, d, seed, noise_sigma) VALUES (?, ?, ?, ?, ?)";
        String insertPoint =
                "INSERT INTO points(dataset_id, idx, sports, games, music, movies, memes, likes, comments) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);

            long datasetId;
            try (PreparedStatement ps = c.prepareStatement(insertDataset, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setInt(2, n);
                ps.setInt(3, Feature.count());
                ps.setLong(4, seed);
                ps.setDouble(5, noiseSigma);
                ps.executeUpdate();

                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("No generated key for dataset");
                    datasetId = keys.getLong(1);
                }
            }

            try (PreparedStatement ps = c.prepareStatement(insertPoint)) {
                for (PointVector p : points) {
                    double[] x = p.x();
                    if (x.length != Feature.count()) {
                        throw new IllegalArgumentException("Point dim != Feature.count()");
                    }

                    ps.setLong(1, datasetId);
                    ps.setInt(2, p.idx());

                    // порядок должен совпадать со схемой
                    ps.setDouble(3, x[0]); // sports
                    ps.setDouble(4, x[1]); // games
                    ps.setDouble(5, x[2]); // music
                    ps.setDouble(6, x[3]); // movies
                    ps.setDouble(7, x[4]); // memes
                    ps.setDouble(8, x[5]); // likes
                    ps.setDouble(9, x[6]); // comments

                    ps.addBatch();
                }
                ps.executeBatch();
            }

            c.commit();
            return datasetId;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to create dataset", e);
        }
    }

    public List<PointVector> loadPoints(long datasetId) {
        String sql = """
        SELECT idx, sports, games, music, movies, memes, likes, comments
        FROM points
        WHERE dataset_id = ?
        ORDER BY idx
        """;

        List<PointVector> out = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setLong(1, datasetId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int idx = rs.getInt("idx");
                    double[] x = new double[Feature.count()];

                    x[0] = rs.getDouble("sports");
                    x[1] = rs.getDouble("games");
                    x[2] = rs.getDouble("music");
                    x[3] = rs.getDouble("movies");
                    x[4] = rs.getDouble("memes");
                    x[5] = rs.getDouble("likes");
                    x[6] = rs.getDouble("comments");

                    out.add(new PointVector(idx, x));
                }
            }
            return out;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to load points for datasetId=" + datasetId, e);
        }
    }

}
