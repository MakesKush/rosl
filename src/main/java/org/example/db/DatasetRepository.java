package org.example.db;

import org.example.model.DatasetInfo;
import org.example.model.Feature;
import org.example.model.PointVector;

import java.io.*;
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
        String sql = "DELETE FROM datasets WHERE id = ?";

        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);

            int affected;
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, datasetId);
                affected = ps.executeUpdate();
            }

            c.commit();
            return affected > 0;

        } catch (Exception e) {
            throw new RuntimeException("Failed to delete dataset id=" + datasetId, e);
        }
    }

    public long createDataset(String name, int n, long seed, double sigma, List<PointVector> points) {
        if (points.size() != n) throw new IllegalArgumentException("points.size != n");

        String insertDataset = "INSERT INTO datasets(name, n, seed, sigma, d) VALUES (?, ?, ?, ?, ?)";
        String insertPoint   = "INSERT INTO points(dataset_id, idx, vec) VALUES (?, ?, ?)";

        Connection c = null;
        try {
            c = Database.getConnection();
            c.setAutoCommit(false);

            long datasetId;
            try (PreparedStatement ps = c.prepareStatement(insertDataset, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setInt(2, n);
                ps.setLong(3, seed);
                ps.setDouble(4, sigma);
                ps.setInt(5, Feature.count()); // 15
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
                    ps.setBytes(3, packVec(x));

                    ps.addBatch();
                }
                ps.executeBatch();
            }

            c.commit();
            return datasetId;

        } catch (Exception e) {
            try { if (c != null) c.rollback(); } catch (Exception ignored) {}
            throw new RuntimeException("Failed to create dataset", e);
        } finally {
            try { if (c != null) c.close(); } catch (Exception ignored) {}
        }
    }

    public List<PointVector> loadPoints(long datasetId) {
        String sql = """
                SELECT idx, vec
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
                    byte[] blob = rs.getBytes("vec");

                    double[] x = unpackVecToDim(blob, Feature.count()); // 15 (старые добьём нулями)
                    out.add(new PointVector(idx, x));
                }
            }

            return out;

        } catch (Exception e) {
            throw new RuntimeException("Failed to load points for datasetId=" + datasetId, e);
        }
    }

    // ---- vec (BLOB) codec ----
    private static byte[] packVec(double[] x) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8 + x.length * 8);
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeInt(x.length);
            for (double v : x) dos.writeDouble(v);
        }
        return bos.toByteArray();
    }

    private static double[] unpackVecToDim(byte[] blob, int targetDim) throws IOException {
        if (blob == null || blob.length == 0) return new double[targetDim];

        double[] raw;
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(blob))) {
            int len = dis.readInt();
            raw = new double[Math.max(0, len)];
            for (int i = 0; i < raw.length; i++) raw[i] = dis.readDouble();
        }

        double[] x = new double[targetDim];
        int m = Math.min(raw.length, x.length);
        System.arraycopy(raw, 0, x, 0, m);
        return x;
    }
}
