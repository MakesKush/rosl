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
