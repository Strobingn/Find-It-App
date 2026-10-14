"""Regenerate owned, deterministic LAZ data. Requires laspy 2.7.0, lazrs 0.8.2, numpy.

This file deliberately uses an independent compressor rather than laszip4j's writer.
Run from the repository root: python scripts/generate_laz_precision_fixture.py
"""
from pathlib import Path
import datetime
import laspy
import numpy as np

count = 450_123  # nine full 50k chunks and a short final chunk
header = laspy.LasHeader(point_format=0, version="1.2")
header.scales = np.array([0.001, 0.001, 0.001])
header.offsets = np.array([500_000.0, 4_500_000.0, 0.0])
header.creation_date = datetime.date(2026, 10, 14)
header.generating_software = "Find It precision regression"
cloud = laspy.LasData(header)
index = np.arange(count, dtype=np.int64)
cloud.X = ((index % 128) * 1_000).astype(np.int32)
cloud.Y = (((index // 128) % 128) * 1_000).astype(np.int32)
cloud.Z = (40_000 + (index % 128) * 20 - ((index // 128) % 128) * 10).astype(np.int32)
cloud.return_number = np.ones(count, dtype=np.uint8)
cloud.number_of_returns = np.ones(count, dtype=np.uint8)
cloud.classification = np.full(count, 2, dtype=np.uint8)
cloud.classification[49_999] = 7
cloud.withheld[50_000] = 1
cloud.classification[450_122] = 18
output = Path("app/src/test/resources/laz/precision-chunks.laz")
output.parent.mkdir(parents=True, exist_ok=True)
cloud.write(output, laz_backend=laspy.LazBackend.Lazrs)
verified = laspy.read(output)
assert len(verified.points) == count
assert np.array_equal(verified.X, cloud.X)
assert np.array_equal(verified.Z, cloud.Z)
assert np.array_equal(verified.withheld, cloud.withheld)
print(f"Verified {count} points, {output.stat().st_size} bytes; laspy {laspy.__version__}")

# Coherent spatial strips exercise chunk pruning; the lattice above intentionally cannot prune.
count = 250_123
header = laspy.LasHeader(point_format=0, version="1.2")
header.scales = np.array([0.001, 0.001, 0.001])
header.offsets = np.array([500_000.0, 4_500_000.0, 0.0])
header.creation_date = datetime.date(2026, 10, 14)
header.generating_software = "Find It viewport regression"
cloud = laspy.LasData(header)
i = np.arange(count, dtype=np.int64)
x = (i % 50_000) % 250
y = (i // 50_000) * 300 + (i % 50_000) // 250
cloud.X = (x * 1_000).astype(np.int32)
cloud.Y = (y * 1_000).astype(np.int32)
cloud.Z = (40_000 + x * 20 - y * 10).astype(np.int32)
cloud.return_number = np.ones(count, dtype=np.uint8)
cloud.number_of_returns = np.ones(count, dtype=np.uint8)
cloud.classification = np.full(count, 2, dtype=np.uint8)
cloud.classification[50_010] = 7
cloud.withheld[50_011] = 1
cloud.classification[50_012] = 18
output = Path("app/src/test/resources/laz/viewport-chunks.laz")
cloud.write(output, laz_backend=laspy.LazBackend.Lazrs)
verified = laspy.read(output)
assert np.array_equal(verified.points.array, cloud.points.array)
print(f"Verified {count} spatially coherent points, {output.stat().st_size} bytes")
