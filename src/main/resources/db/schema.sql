CREATE TABLE IF NOT EXISTS DATASETS (
                                        ID IDENTITY PRIMARY KEY,
                                        NAME VARCHAR(255) NOT NULL,
    N INT NOT NULL,
    SEED BIGINT NOT NULL,
    SIGMA DOUBLE NOT NULL,
    D INT NOT NULL,
    CREATED_AT TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

CREATE TABLE IF NOT EXISTS POINTS (
                                      ID IDENTITY PRIMARY KEY,
                                      DATASET_ID BIGINT NOT NULL,
                                      IDX INT NOT NULL,
                                      VEC BLOB NOT NULL,
                                      CONSTRAINT FK_POINTS_DATASETS
                                      FOREIGN KEY (DATASET_ID) REFERENCES DATASETS(ID) ON DELETE CASCADE
    );

CREATE TABLE IF NOT EXISTS RUNS (
                                    ID IDENTITY PRIMARY KEY,
                                    DATASET_ID BIGINT NOT NULL,
                                    MODE VARCHAR(32) NOT NULL,
    K INT NOT NULL,
    THREADS INT NOT NULL,
    MAX_ITER INT NOT NULL,
    EPS DOUBLE NOT NULL,
    STATUS VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    STOP_REASON VARCHAR(128),
    CREATED_AT TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FINISHED_AT TIMESTAMP,
    CONSTRAINT FK_RUNS_DATASETS
    FOREIGN KEY (DATASET_ID) REFERENCES DATASETS(ID) ON DELETE CASCADE
    );

CREATE TABLE IF NOT EXISTS ITER_METRICS (
                                            ID IDENTITY PRIMARY KEY,
                                            RUN_ID BIGINT NOT NULL,
                                            ITER INT NOT NULL,
                                            SSE DOUBLE NOT NULL,
                                            ASSIGN_MS DOUBLE NOT NULL,
                                            UPDATE_MS DOUBLE NOT NULL,
                                            TOTAL_MS DOUBLE NOT NULL,
                                            CONSTRAINT FK_ITER_METRICS_RUNS
                                            FOREIGN KEY (RUN_ID) REFERENCES RUNS(ID) ON DELETE CASCADE
    );

CREATE TABLE IF NOT EXISTS RUN_METRICS (
                                           RUN_ID BIGINT PRIMARY KEY,
                                           TOTAL_MS BIGINT NOT NULL,
                                           ITERS INT NOT NULL,
                                           FINAL_SSE DOUBLE NOT NULL,
                                           AVG_ITER_MS DOUBLE NOT NULL,
                                           AVG_ASSIGN_MS DOUBLE NOT NULL,
                                           AVG_UPDATE_MS DOUBLE NOT NULL,
                                           CONSTRAINT FK_RUN_METRICS_RUNS
                                           FOREIGN KEY (RUN_ID) REFERENCES RUNS(ID) ON DELETE CASCADE
    );

CREATE TABLE IF NOT EXISTS RESULTS (
                                       RUN_ID BIGINT PRIMARY KEY,
                                       CENTROIDS BLOB NOT NULL,
                                       ASSIGNMENTS BLOB NOT NULL,
                                       CONSTRAINT FK_RESULTS_RUNS
                                       FOREIGN KEY (RUN_ID) REFERENCES RUNS(ID) ON DELETE CASCADE
    );

CREATE TABLE IF NOT EXISTS CLUSTER_METRICS (
                                               ID IDENTITY PRIMARY KEY,
                                               RUN_ID BIGINT NOT NULL,
                                               CLUSTER_ID INT NOT NULL,
                                               SIZE INT NOT NULL,
                                               SSE DOUBLE NOT NULL,
                                               AVG_DIST DOUBLE NOT NULL,
                                               MAX_DIST DOUBLE NOT NULL,
                                               CONSTRAINT FK_CLUSTER_METRICS_RUNS
                                               FOREIGN KEY (RUN_ID) REFERENCES RUNS(ID) ON DELETE CASCADE
    );

ALTER TABLE DATASETS ADD COLUMN IF NOT EXISTS SIGMA DOUBLE;
ALTER TABLE DATASETS ADD COLUMN IF NOT EXISTS NOISE_SIGMA DOUBLE;

UPDATE DATASETS
SET SIGMA = COALESCE(SIGMA, NOISE_SIGMA)
WHERE SIGMA IS NULL;

ALTER TABLE DATASETS ADD COLUMN IF NOT EXISTS CREATED_AT TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE POINTS ADD COLUMN IF NOT EXISTS VEC BLOB;

CREATE INDEX IF NOT EXISTS IDX_POINTS_DATASET ON POINTS(DATASET_ID);