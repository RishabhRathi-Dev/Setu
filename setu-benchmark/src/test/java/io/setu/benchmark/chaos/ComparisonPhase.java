package io.setu.benchmark.chaos;

import org.HdrHistogram.Histogram;

record ComparisonPhase(Histogram histogram, long rejected, long minLaneRequests, long maxLaneRequests) { }
