/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.transforms;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.cloud.dataflow.sdk.annotations.Experimental;
import com.google.cloud.dataflow.sdk.annotations.Experimental.Kind;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.transforms.Combine.CombineFn;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.util.WindowingInternals;
import com.google.cloud.dataflow.sdk.values.CodedTupleTag;
import com.google.cloud.dataflow.sdk.values.CodedTupleTagMap;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.TupleTag;
import com.google.cloud.dataflow.sdk.values.TypeDescriptor;

import org.joda.time.Duration;
import org.joda.time.Instant;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * The argument to {@link ParDo} providing the code to use to process
 * elements of the input
 * {@link com.google.cloud.dataflow.sdk.values.PCollection}.
 *
 * <p> See {@link ParDo} for more explanation, examples of use, and
 * discussion of constraints on {@code DoFn}s, including their
 * serializability, lack of access to global shared mutable state,
 * requirements for failure tolerance, and benefits of optimization.
 *
 * <p> {@code DoFn}s can be tested in the context of a particular
 * {@code Pipeline} by running that {@code Pipeline} on sample input
 * and then checking its output.  Unit testing of a {@code DoFn},
 * separately from any {@code ParDo} transform or {@code Pipeline},
 * can be done via the {@link DoFnTester} harness.
 *
 * <p> {@link DoFnWithContext} (currently experimental) offers an alternative
 * mechanism for accessing {@link ProcessContext#keyedState} and
 * {@link ProcessContext#window()} without the need to implement
 * {@link RequiresKeyedState} or {@link RequiresWindowAccess}.
 *
 * @param <InputT> the type of the (main) input elements
 * @param <OutputT> the type of the (main) output elements
 */
@SuppressWarnings("serial")
public abstract class DoFn<InputT, OutputT> implements Serializable {

  /** Information accessible to all methods in this {@code DoFn}. */
  public abstract class Context {

    /**
     * Returns the {@code PipelineOptions} specified with the
     * {@link com.google.cloud.dataflow.sdk.runners.PipelineRunner}
     * invoking this {@code DoFn}.  The {@code PipelineOptions} will
     * be the default running via {@link DoFnTester}.
     */
    public abstract PipelineOptions getPipelineOptions();

    /**
     * Adds the given element to the main output {@code PCollection}.
     *
     * <p> Once passed to {@code output} the element should not be modified in
     * any way.
     *
     * <p> If invoked from {@link DoFn#processElement}, the output
     * element will have the same timestamp and be in the same windows
     * as the input element passed to {@link DoFn#processElement}).
     *
     * <p> If invoked from {@link #startBundle} or {@link #finishBundle},
     * this will attempt to use the
     * {@link com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn}
     * of the input {@code PCollection} to determine what windows the element
     * should be in, throwing an exception if the {@code WindowFn} attempts
     * to access any information about the input element. The output element
     * will have a timestamp of negative infinity.
     */
    public abstract void output(OutputT output);

    /**
     * Adds the given element to the main output {@code PCollection},
     * with the given timestamp.
     *
     * <p> Once passed to {@code outputWithTimestamp} the element should not be
     * modified in any way.
     *
     * <p> If invoked from {@link DoFn#processElement}), the timestamp
     * must not be older than the input element's timestamp minus
     * {@link DoFn#getAllowedTimestampSkew}.  The output element will
     * be in the same windows as the input element.
     *
     * <p> If invoked from {@link #startBundle} or {@link #finishBundle},
     * this will attempt to use the
     * {@link com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn}
     * of the input {@code PCollection} to determine what windows the element
     * should be in, throwing an exception if the {@code WindowFn} attempts
     * to access any information about the input element except for the
     * timestamp.
     */
    public abstract void outputWithTimestamp(OutputT output, Instant timestamp);

    /**
     * Adds the given element to the side output {@code PCollection} with the
     * given tag.
     *
     * <p> Once passed to {@code sideOutput} the element should not be modified
     * in any way.
     *
     * <p> The caller of {@code ParDo} uses {@link ParDo#withOutputTags} to
     * specify the tags of side outputs that it consumes. Non-consumed side
     * outputs, e.g., outputs for monitoring purposes only, don't necessarily
     * need to be specified.
     *
     * <p> The output element will have the same timestamp and be in the same
     * windows as the input element passed to {@link DoFn#processElement}).
     *
     * <p> If invoked from {@link #startBundle} or {@link #finishBundle},
     * this will attempt to use the
     * {@link com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn}
     * of the input {@code PCollection} to determine what windows the element
     * should be in, throwing an exception if the {@code WindowFn} attempts
     * to access any information about the input element. The output element
     * will have a timestamp of negative infinity.
     *
     * @throws IllegalArgumentException if the number of outputs exceeds
     * the limit of 1,000 outputs per DoFn
     * @see ParDo#withOutputTags
     */
    public abstract <T> void sideOutput(TupleTag<T> tag, T output);

    /**
     * Adds the given element to the specified side output {@code PCollection},
     * with the given timestamp.
     *
     * <p> Once passed to {@code sideOutputWithTimestamp} the element should not be
     * modified in any way.
     *
     * <p> If invoked from {@link DoFn#processElement}), the timestamp
     * must not be older than the input element's timestamp minus
     * {@link DoFn#getAllowedTimestampSkew}.  The output element will
     * be in the same windows as the input element.
     *
     * <p> If invoked from {@link #startBundle} or {@link #finishBundle},
     * this will attempt to use the
     * {@link com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn}
     * of the input {@code PCollection} to determine what windows the element
     * should be in, throwing an exception if the {@code WindowFn} attempts
     * to access any information about the input element except for the
     * timestamp.
     *
     * @throws IllegalArgumentException if the number of outputs exceeds
     * the limit of 1,000 outputs per DoFn
     * @see ParDo#withOutputTags
     */
    public abstract <T> void sideOutputWithTimestamp(
        TupleTag<T> tag, T output, Instant timestamp);

    /**
     * Creates an {@link Aggregator} in the {@link DoFn} context with the
     * specified name and aggregation logic specified by {@link CombineFn}.
     *
     * <p>For internal use only.
     *
     * @param name the name of the aggregator
     * @param combiner the {@link CombineFn} to use in the aggregator
     * @return an aggregator for the provided name and {@link CombineFn} in this
     *         context
     */
    @Experimental(Kind.AGGREGATOR)
    protected abstract <AggInputT, AggOutputT> Aggregator<AggInputT, AggOutputT>
        createAggregatorInternal(String name, CombineFn<AggInputT, ?, AggOutputT> combiner);

    /**
     * Sets up {@link Aggregator}s created by the {@link DoFn} so they are
     * usable within this context.
     *
     * <p>This method should be called by runners before {@link DoFn#startBundle}
     * is executed.
     */
    @Experimental(Kind.AGGREGATOR)
    protected final void setupDelegateAggregators() {
      for (DelegatingAggregator<?, ?> aggregator : aggregators.values()) {
        setupDelegateAggregator(aggregator);
      }
    }

    private final <AggInputT, AggOutputT> void setupDelegateAggregator(
        DelegatingAggregator<AggInputT, AggOutputT> aggregator) {

      Aggregator<AggInputT, AggOutputT> delegate = createAggregatorInternal(
          aggregator.getName(), aggregator.getCombineFn());

      aggregator.setDelegate(delegate);
    }
  }

  /**
   * Information accessible when running {@link DoFn#processElement}.
   */
  public abstract class ProcessContext extends Context {

    /**
     * Returns the input element to be processed.
     *
     * <p> The element will not be changed -- it is safe to cache, etc.
     * without copying.
     */
    public abstract InputT element();

    /**
     * Returns the value of the side input for the window corresponding to the
     * window of the main input element.
     *
     * <p> See
     * {@link com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn#getSideInputWindow}
     * for how this corresponding window is determined.
     *
     * @throws IllegalArgumentException if this is not a side input
     * @see ParDo#withSideInputs
     */
    public abstract <T> T sideInput(PCollectionView<T> view);

    /**
     * Returns this {@code DoFn}'s state associated with the input
     * element's key.  This state can be used by the {@code DoFn} to
     * store whatever information it likes with that key.  Unlike
     * {@code DoFn} instance variables, this state is persistent and
     * can be arbitrarily large; it is more expensive than instance
     * variable state, however.  It is particularly intended for
     * streaming computations.
     *
     * <p> Requires that this {@code DoFn} implements
     * {@link RequiresKeyedState}.
     *
     * <p> Each {@link ParDo} invocation with this {@code DoFn} as an
     * argument will maintain its own {@code KeyedState} maps, one per
     * key.
     *
     * @throws UnsupportedOperationException if this {@link DoFn} does
     * not implement {@link RequiresKeyedState}.
     */
    public abstract KeyedState keyedState();

    /**
     * Returns the timestamp of the input element.
     *
     * <p> See {@link com.google.cloud.dataflow.sdk.transforms.windowing.Window}
     * for more information.
     */
    public abstract Instant timestamp();

    /**
     * Returns the window into which the input element has been assigned.
     *
     * <p> See {@link com.google.cloud.dataflow.sdk.transforms.windowing.Window}
     * for more information.
     *
     * @throws UnsupportedOperationException if this {@link DoFn} does
     * not implement {@link RequiresWindowAccess}.
     */
    public abstract BoundedWindow window();

    /**
     * Returns the process context to use for implementing windowing.
     */
    @Experimental
    public abstract WindowingInternals<InputT, OutputT> windowingInternals();
  }

  /**
   * Returns the allowed timestamp skew duration, which is the maximum
   * duration that timestamps can be shifted backward in
   * {@link DoFn.Context#outputWithTimestamp}.
   *
   * <p> The default value is {@code Duration.ZERO}, in which case
   * timestamps can only be shifted forward to future.  For infinite
   * skew, return {@code Duration.millis(Long.MAX_VALUE)}.
   */
  public Duration getAllowedTimestampSkew() {
    return Duration.ZERO;
  }

  /**
   * Interface for signaling that a {@link DoFn} needs to maintain
   * per-key state, accessed via
   * {@link DoFn.ProcessContext#keyedState}.
   */
  @Experimental
  public interface RequiresKeyedState {}

  /**
   * Interface for signaling that a {@link DoFn} needs to access the window the
   * element is being processed in, via {@link DoFn.ProcessContext#window}.
   */
  @Experimental
  public interface RequiresWindowAccess {}

  /**
   * {@code KeyedState} maps {@link CodedTupleTag CodedTupleTags} to
   * associated values.  The storage is persistent across bundles, and
   * stored per-key. Specifically, for a given {@code CodedTupleTag<T>},
   * each key will store a distinct {@code T} value.
   */
  @Experimental
  public interface KeyedState {
    /**
     * Updates this {@code KeyedState} in place so that the given tag maps to the given value.
     *
     * @throws IOException if encoding the given value fails
     */
    public <T> void store(CodedTupleTag<T> tag, T value) throws IOException;

    /**
     * Removes the data associated with the given tag from {@code KeyedState}.
     */
    public <T> void remove(CodedTupleTag<T> tag);

    /**
     * Returns the value associated with the given tag in this
     * {@code KeyedState}, or {@code null} if the tag has no asssociated
     * value.
     *
     * <p> See {@link #lookup(Iterable)} to look up multiple tags at
     * once.  It is significantly more efficient to look up multiple
     * tags all at once rather than one at a time.
     *
     * @throws IOException if decoding the requested value fails
     */
    public <T> T lookup(CodedTupleTag<T> tag) throws IOException;

    /**
     * Returns a map from the given tags to the values associated with
     * those tags in this {@code KeyedState}.  A tag will map to null if
     * the tag had no associated value.
     *
     * <p> See {@link #lookup(CodedTupleTag)} to look up a single
     * tag.
     *
     * @throws IOException if decoding any of the requested values fails, often
     * a {@link com.google.cloud.dataflow.sdk.coders.CoderException}.
     */
    public CodedTupleTagMap lookup(Iterable<? extends CodedTupleTag<?>> tags) throws IOException;
  }

  public DoFn() {
    this(new HashMap<String, DelegatingAggregator<?, ?>>());
  }

  DoFn(Map<String, DelegatingAggregator<?, ?>> aggregators) {
    this.aggregators = aggregators;
  }

  /////////////////////////////////////////////////////////////////////////////

  private final Map<String, DelegatingAggregator<?, ?>> aggregators;

  /**
   * Prepares this {@code DoFn} instance for processing a batch of elements.
   *
   * <p> By default, does nothing.
   */
  public void startBundle(Context c) throws Exception {
  }

  /**
   * Processes an input element.
   */
  public abstract void processElement(ProcessContext c) throws Exception;

  /**
   * Finishes processing this batch of elements.  This {@code DoFn}
   * instance will be thrown away after this operation returns.
   *
   * <p> By default, does nothing.
   */
  public void finishBundle(Context c) throws Exception {
  }


  /////////////////////////////////////////////////////////////////////////////

  /**
   * Returns a {@link TypeDescriptor} capturing what is known statically
   * about the input type of this {@code DoFn} instance's most-derived
   * class.
   *
   * <p> See {@link #getOutputTypeDescriptor} for more discussion.
   */
  protected TypeDescriptor<InputT> getInputTypeDescriptor() {
    return new TypeDescriptor<InputT>(getClass()) {};
  }

  /**
   * Returns a {@link TypeDescriptor} capturing what is known statically
   * about the output type of this {@code DoFn} instance's
   * most-derived class.
   *
   * <p> In the normal case of a concrete {@code DoFn} subclass with
   * no generic type parameters of its own (including anonymous inner
   * classes), this will be a complete non-generic type, which is good
   * for choosing a default output {@code Coder<OutputT>} for the output
   * {@code PCollection<OutputT>}.
   */
  protected TypeDescriptor<OutputT> getOutputTypeDescriptor() {
    return new TypeDescriptor<OutputT>(getClass()) {};
  }

  /**
   * Returns an {@link Aggregator} with aggregation logic specified by the
   * {@link CombineFn} argument. The name provided must be unique across
   * {@link Aggregator}s created within the DoFn.
   *
   * @param name the name of the aggregator
   * @param combiner the {@link CombineFn} to use in the aggregator
   * @return an aggregator for the provided name and combiner in the scope of
   *         this DoFn
   * @throws NullPointerException if the name or combiner is null
   * @throws IllegalArgumentException if the given name collides with another
   *         aggregator in this scope
   */
  protected final <AggInputT, AggOutputT> Aggregator<AggInputT, AggOutputT>
      createAggregator(String name, CombineFn<? super AggInputT, ?, AggOutputT> combiner) {
    checkNotNull(name, "name cannot be null");
    checkNotNull(combiner, "combiner cannot be null");
    checkArgument(!aggregators.containsKey(name),
        "Cannot create aggregator with name %s."
        + " An Aggregator with that name already exists within this scope.",
        name);
    DelegatingAggregator<AggInputT, AggOutputT> aggregator =
        new DelegatingAggregator<>(name, combiner);
    aggregators.put(name, aggregator);
    return aggregator;
  }

  /**
   * Returns an {@link Aggregator} with the aggregation logic specified by the
   * {@link SerializableFunction} argument. The name provided must be unique
   * across {@link Aggregator}s created within the DoFn.
   *
   * @param name the name of the aggregator
   * @param combiner the {@link SerializableFunction} to use in the aggregator
   * @return an aggregator for the provided name and combiner in the scope of
   *         this DoFn
   * @throws NullPointerException if the name or combiner is null
   * @throws IllegalArgumentException if the given name collides with another
   *         aggregator in this scope
   */
  protected final <AggInputT> Aggregator<AggInputT, AggInputT> createAggregator(String name,
      SerializableFunction<Iterable<AggInputT>, AggInputT> combiner) {
    checkNotNull(combiner, "combiner cannot be null.");
    return createAggregator(name, Combine.SimpleCombineFn.of(combiner));
  }

  /**
   * An {@link Aggregator} that delegates calls to addValue to another
   * aggregator.
   *
   * @param <AggInputT> the type of input element
   * @param <AggOutputT> the type of output element
   */
  static class DelegatingAggregator<AggInputT, AggOutputT> implements
      Aggregator<AggInputT, AggOutputT>, Serializable {
    private static final long serialVersionUID = 0L;

    private final String name;

    private final CombineFn<AggInputT, ?, AggOutputT> combineFn;

    private Aggregator<AggInputT, ?> delegate;

    public DelegatingAggregator(String name,
        CombineFn<? super AggInputT, ?, AggOutputT> combiner) {
      this.name = name;
      // Safe contravariant cast
      @SuppressWarnings("unchecked")
      CombineFn<AggInputT, ?, AggOutputT> specificCombiner =
          (CombineFn<AggInputT, ?, AggOutputT>) combiner;
      this.combineFn = specificCombiner;
    }

    @Override
    public void addValue(AggInputT value) {
      if (delegate == null) {
        throw new IllegalStateException(
            "addValue cannot be called on Aggregator outside of the execution of a DoFn.");
      } else {
        delegate.addValue(value);
      }
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public CombineFn<AggInputT, ?, AggOutputT> getCombineFn() {
      return combineFn;
    }

    /**
     * Sets the current delegate of the Aggregator.
     *
     * @param delegate the delegate to set in this aggregator
     */
    public void setDelegate(Aggregator<AggInputT, ?> delegate) {
      this.delegate = delegate;
    }
  }
}
