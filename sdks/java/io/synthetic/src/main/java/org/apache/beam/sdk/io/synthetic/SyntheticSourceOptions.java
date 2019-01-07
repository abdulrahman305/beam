/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.synthetic;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.apache.beam.sdk.values.KV;
import org.apache.commons.math3.distribution.ConstantRealDistribution;
import org.joda.time.Duration;

/**
 * Synthetic bounded source options. These options are all JSON, see documentations of individual
 * fields for details. {@code SyntheticSourceOptions} uses jackson annotations which
 * PipelineOptionsFactory can use to parse and construct an instance.
 */
public class SyntheticSourceOptions extends SyntheticOptions {
  private static final long serialVersionUID = 0;

  /** Total number of generated records. */
  @JsonProperty public long numRecords;

  /**
   * Only records whose index is a multiple of this will be split points. 0 means the source is not
   * dynamically splittable (but is perfectly statically splittable). In that case it also doesn't
   * report progress at all.
   */
  @JsonProperty public long splitPointFrequencyRecords = 1;

  /**
   * Distribution for generating initial split bundles.
   *
   * <p>When splitting into "desiredBundleSizeBytes", we'll compute the desired number of bundles N,
   * then sample this many numbers from this distribution, normalize their sum to 1, and use that as
   * the boundaries of generated bundles.
   *
   * <p>The Zipf distribution is expected to be particularly useful here.
   *
   * <p>E.g., empirically, with 100 bundles, the Zipf distribution with a parameter of 3.5 will
   * generate bundles where the largest is about 3x-10x larger than the median; with a parameter of
   * 3.0 this ratio will be about 5x-50x; with 2.5, 5x-100x (i.e. 1 bundle can be as large as all
   * others combined).
   */
  @JsonDeserialize(using = SamplerDeserializer.class)
  public Sampler bundleSizeDistribution = fromRealDistribution(new ConstantRealDistribution(1));

  /**
   * If specified, this source will split into exactly this many bundles regardless of the hints
   * provided by the service.
   */
  @JsonProperty public Integer forceNumInitialBundles;

  /** See {@link ProgressShape}. */
  @JsonProperty public ProgressShape progressShape = ProgressShape.LINEAR;

  /**
   * The distribution for the delay when reading from synthetic source starts. This delay is
   * independent of the per-record delay and uses the same types of distributions as {@link
   * #delayDistribution}.
   */
  @JsonDeserialize(using = SamplerDeserializer.class)
  final Sampler initializeDelayDistribution = fromRealDistribution(new ConstantRealDistribution(0));

  /**
   * Generates a random delay value for the synthetic source initialization using the distribution
   * defined by {@link #initializeDelayDistribution}.
   */
  public Duration nextInitializeDelay(long seed) {
    return Duration.millis((long) initializeDelayDistribution.sample(seed));
  }

  /**
   * The delay between event and processing time. uses same types of distributions as any other
   * delay in {@link SyntheticSourceOptions}.
   *
   * <p>Example: we can use ConstantRealDistribution(10) to simulate constant 10 millis delay
   * between event and processing times for each record generated by UnboundedSyntheticSource.
   */
  @JsonDeserialize(using = SamplerDeserializer.class)
  Sampler processingTimeDelayDistribution = fromRealDistribution(new ConstantRealDistribution(0));

  /**
   * Generates a random delay value between event and processing time using the distribution defined
   * by {@link #processingTimeDelayDistribution}.
   */
  public Duration nextProcessingTimeDelay(long seed) {
    return Duration.millis((long) processingTimeDelayDistribution.sample(seed));
  }

  /**
   * Defines how many elements should the watermark function check in advance to "predict" how the
   * record distribution will look like.
   */
  @JsonProperty public Integer watermarkSearchInAdvanceCount = 100;

  /**
   * Could be either positive and negative. Positive drift will "push away" the watermark from the
   * actual records event times. Negative will bring it closer, possibly causing some events to be
   * "late".
   *
   * <p>By default there is no drift at all.
   */
  @JsonProperty public Integer watermarkDriftMillis = 0;

  @Override
  public void validate() {
    super.validate();
    checkArgument(
        numRecords >= 0, "numRecords should be a non-negative number, but found %s.", numRecords);
    checkNotNull(bundleSizeDistribution, "bundleSizeDistribution");
    checkArgument(
        forceNumInitialBundles == null || forceNumInitialBundles > 0,
        "forceNumInitialBundles, if specified, must be positive, but found %s",
        forceNumInitialBundles);
    checkArgument(
        splitPointFrequencyRecords >= 0,
        "splitPointFrequencyRecords must be non-negative, but found %s",
        splitPointFrequencyRecords);
  }

  public Record genRecord(long position) {
    // This method is supposed to generate random records deterministically,
    // so that results can be reproduced by running the same scenario a second time.
    // We need to initiate a Random object for each position to make the record deterministic
    // because liquid sharding could split the Source at any position.
    // And we also need a seed to initiate a Random object. The mapping from the position to
    // the seed should be fixed. Using the position as seed to feed Random objects will cause the
    // generated values to not be random enough because the position values are
    // close to each other. To make seeds fed into the Random objects unrelated,
    // we use a hashing function to map the position to its corresponding hashcode,
    // and use the hashcode as a seed to feed into the Random object.
    long hashCodeOfPosition = hashFunction().hashLong(position).asLong();
    return new Record(genKvPair(hashCodeOfPosition), nextDelay(hashCodeOfPosition));
  }

  /** Record generated by {@link #genRecord}. */
  public static class Record {
    public final KV<byte[], byte[]> kv;
    public final Duration sleepMsec;

    Record(KV<byte[], byte[]> kv, long sleepMsec) {
      this.kv = kv;
      this.sleepMsec = new Duration(sleepMsec);
    }
  }

  /**
   * Shape of the progress reporting curve as a function of the current offset in the {@link
   * SyntheticBoundedSource}.
   */
  public enum ProgressShape {
    /** Reported progress grows linearly from 0 to 1. */
    LINEAR,
    /** Reported progress decreases linearly from 0.9 to 0.1. */
    LINEAR_REGRESSING,
  }
}
