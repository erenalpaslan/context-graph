# GRAPH_REPORT

Generated: 2026-06-14T19:19:33.535852Z

## Project Overview

| Metric | Count |
|--------|-------|
| Artifacts | 7 |
| Nodes | 35 |
| Edges | 31 |
| Connected Components | 4 |

### Artifact Breakdown

- **CodeFile**: 2
- **ConfigFile**: 1
- **PackageFile**: 1
- **TestFile**: 1
- **DatabaseSchema**: 1
- **Document**: 1

## Key Entities (by PageRank)

- **org.junit.jupiter:junit-jupiter:5.10.0** [Module] — rank=0.0330, confidence=0.95
- **org.mockito:mockito-core:5.7.0** [Module] — rank=0.0330, confidence=0.95
- **users** [DatabaseTable] — rank=0.0330, confidence=0.99
- **sessions** [DatabaseTable] — rank=0.0330, confidence=0.99
- **AuthController** [Class] — rank=0.0315, confidence=0.98
- **login** [Function] — rank=0.0315, confidence=0.98
- **register** [Function] — rank=0.0315, confidence=0.98
- **id** [Column] — rank=0.0301, confidence=0.99
- **user_id** [Column] — rank=0.0301, confidence=0.99
- **created_at** [Column] — rank=0.0301, confidence=0.99

## Major Clusters

### Cluster 1 (19 nodes)
Sample: UserService.kt, org.junit.jupiter.api.Assertions.assertNull, org.junit.jupiter.api.Test

### Cluster 2 (12 nodes)
Sample: users, schema.sql, sessions

### Cluster 3 (3 nodes)
Sample: build.gradle.kts, org.mockito:mockito-core:5.7.0, org.junit.jupiter:junit-jupiter:5.10.0

### Cluster 4 (1 nodes)
Sample: graph.db

## High-Degree Nodes (Important Dependencies)

- **UserService.kt** [CodeFile] — degree=9
- **users** [DatabaseTable] — degree=6
- **sessions** [DatabaseTable] — degree=5
- **UserServiceTest.kt** [TestFile] — degree=5
- **AuthController.kt** [CodeFile] — degree=4
- **com.example** [Package] — degree=3
- **build.gradle.kts** [PackageFile] — degree=2
- **schema.sql** [DatabaseSchema] — degree=2
- **org.junit.jupiter:junit-jupiter:5.10.0** [Module] — degree=1
- **org.mockito:mockito-core:5.7.0** [Module] — degree=1

## Isolated Nodes (No Connections)

- graph.db [Document]

