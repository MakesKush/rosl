CREATE TABLE IF NOT EXISTS datasets (
                                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                        name VARCHAR(200) NOT NULL,
    n INT NOT NULL,
    d INT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    seed BIGINT,
    noise_sigma DOUBLE
    );

-- Фиксированный набор фич:
-- sports, games, music, movies, memes, likes, comments
CREATE TABLE IF NOT EXISTS points (
                                      dataset_id BIGINT NOT NULL,
                                      idx INT NOT NULL,

                                      sports DOUBLE NOT NULL,
                                      games DOUBLE NOT NULL,
                                      music DOUBLE NOT NULL,
                                      movies DOUBLE NOT NULL,
                                      memes DOUBLE NOT NULL,
                                      likes DOUBLE NOT NULL,
                                      comments DOUBLE NOT NULL,

                                      PRIMARY KEY (dataset_id, idx),
    CONSTRAINT fk_points_dataset FOREIGN KEY (dataset_id) REFERENCES datasets(id) ON DELETE CASCADE
    );

CREATE INDEX IF NOT EXISTS idx_points_dataset ON points(dataset_id);

CREATE TABLE IF NOT EXISTS runs (
                                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                    dataset_id BIGINT NOT NULL,
                                    mode VARCHAR(20) NOT NULL,        -- DEMO / BENCHMARK
    k INT NOT NULL,
    threads INT NOT NULL,
    max_iter INT NOT NULL,
    eps DOUBLE NOT NULL,
    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP,
    stop_reason VARCHAR(50),
    CONSTRAINT fk_runs_dataset FOREIGN KEY (dataset_id) REFERENCES datasets(id) ON DELETE CASCADE
    );

CREATE TABLE IF NOT EXISTS assignments (
                                           run_id BIGINT NOT NULL,
                                           point_idx INT NOT NULL,
                                           cluster_id INT NOT NULL,
                                           PRIMARY KEY (run_id, point_idx),
    CONSTRAINT fk_assign_run FOREIGN KEY (run_id) REFERENCES runs(id) ON DELETE CASCADE
    );

CREATE TABLE IF NOT EXISTS centroids (
                                         run_id BIGINT NOT NULL,
                                         cluster_id INT NOT NULL,

                                         sports DOUBLE NOT NULL,
                                         games DOUBLE NOT NULL,
                                         music DOUBLE NOT NULL,
                                         movies DOUBLE NOT NULL,
                                         memes DOUBLE NOT NULL,
                                         likes DOUBLE NOT NULL,
                                         comments DOUBLE NOT NULL,

                                         PRIMARY KEY (run_id, cluster_id),
    CONSTRAINT fk_centroids_run FOREIGN KEY (run_id) REFERENCES runs(id) ON DELETE CASCADE
    );

CREATE TABLE IF NOT EXISTS run_metrics (
                                           run_id BIGINT PRIMARY KEY,
                                           total_ms BIGINT,
                                           iterations INT,
                                           final_sse DOUBLE,
                                           avg_iter_ms DOUBLE,
                                           avg_assign_ms DOUBLE,
                                           avg_update_ms DOUBLE,
                                           CONSTRAINT fk_rm_run FOREIGN KEY (run_id) REFERENCES runs(id) ON DELETE CASCADE
    );

CREATE TABLE IF NOT EXISTS cluster_metrics (
                                               run_id BIGINT NOT NULL,
                                               cluster_id INT NOT NULL,
                                               size INT,
                                               cluster_sse DOUBLE,
                                               avg_dist DOUBLE,
                                               max_dist DOUBLE,
                                               PRIMARY KEY (run_id, cluster_id),
    CONSTRAINT fk_cm_run FOREIGN KEY (run_id) REFERENCES runs(id) ON DELETE CASCADE
    );

CREATE TABLE IF NOT EXISTS iter_metrics (
                                            run_id BIGINT NOT NULL,
                                            iter INT NOT NULL,
                                            sse DOUBLE,
                                            assign_ms DOUBLE,
                                            update_ms DOUBLE,
                                            total_ms DOUBLE,
                                            PRIMARY KEY (run_id, iter),
    CONSTRAINT fk_im_run FOREIGN KEY (run_id) REFERENCES runs(id) ON DELETE CASCADE
    );

CREATE INDEX IF NOT EXISTS idx_runs_dataset ON runs(dataset_id);
CREATE INDEX IF NOT EXISTS idx_assignments_run ON assignments(run_id);
CREATE INDEX IF NOT EXISTS idx_centroids_run ON centroids(run_id);
CREATE INDEX IF NOT EXISTS idx_iter_metrics_run ON iter_metrics(run_id);
