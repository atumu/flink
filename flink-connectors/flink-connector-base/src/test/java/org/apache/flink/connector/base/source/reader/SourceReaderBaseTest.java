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

package org.apache.flink.connector.base.source.reader;

import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.api.connector.source.SourceReader;
import org.apache.flink.api.connector.source.mocks.MockSourceSplit;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.base.source.reader.mocks.MockSourceReader;
import org.apache.flink.connector.base.source.reader.mocks.MockSplitReader;
import org.apache.flink.connector.base.source.reader.mocks.PassThroughRecordEmitter;
import org.apache.flink.connector.base.source.reader.mocks.TestingRecordsWithSplitIds;
import org.apache.flink.connector.base.source.reader.mocks.TestingSourceSplit;
import org.apache.flink.connector.base.source.reader.mocks.TestingSplitReader;
import org.apache.flink.connector.base.source.reader.splitreader.SplitReader;
import org.apache.flink.connector.base.source.reader.splitreader.SplitsChange;
import org.apache.flink.connector.base.source.reader.synchronization.FutureCompletingBlockingQueue;
import org.apache.flink.connector.testutils.source.reader.SourceReaderTestBase;
import org.apache.flink.connector.testutils.source.reader.TestingReaderContext;
import org.apache.flink.connector.testutils.source.reader.TestingReaderOutput;
import org.apache.flink.core.io.InputStatus;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * A unit test class for {@link SourceReaderBase}.
 */
public class SourceReaderBaseTest extends SourceReaderTestBase<MockSourceSplit> {

	@Rule
	public ExpectedException expectedException = ExpectedException.none();

	@Test
	public void testExceptionInSplitReader() throws Exception {
		expectedException.expect(RuntimeException.class);
		expectedException.expectMessage("One or more fetchers have encountered exception");
		final String errMsg = "Testing Exception";

		FutureCompletingBlockingQueue<RecordsWithSplitIds<int[]>> elementsQueue =
			new FutureCompletingBlockingQueue<>();
		// We have to handle split changes first, otherwise fetch will not be called.
		try (MockSourceReader reader = new MockSourceReader(
			elementsQueue,
			() -> new SplitReader<int[], MockSourceSplit>() {
				@Override
				public RecordsWithSplitIds<int[]> fetch() {
					throw new RuntimeException(errMsg);
				}

				@Override
				public void handleSplitsChanges(SplitsChange<MockSourceSplit> splitsChanges) {}

				@Override
				public void wakeUp() {}

				@Override
				public void close() throws Exception {}
			},
			getConfig(),
			null)) {
			ValidatingSourceOutput output = new ValidatingSourceOutput();
			reader.addSplits(Collections.singletonList(getSplit(0,
				NUM_RECORDS_PER_SPLIT,
				Boundedness.CONTINUOUS_UNBOUNDED)));
			reader.notifyNoMoreSplits();
			// This is not a real infinite loop, it is supposed to throw exception after two polls.
			while (true) {
				InputStatus inputStatus = reader.pollNext(output);
				assertNotEquals(InputStatus.END_OF_INPUT, inputStatus);
				// Add a sleep to avoid tight loop.
				Thread.sleep(1);
			}
		}
	}

	@Test
	public void testRecordsWithSplitsNotRecycledWhenRecordsLeft() throws Exception {
		final TestingRecordsWithSplitIds<String> records = new TestingRecordsWithSplitIds<>("test-split", "value1", "value2");
		final SourceReader<?, ?> reader = createReaderAndAwaitAvailable("test-split", records);

		reader.pollNext(new TestingReaderOutput<>());

		assertFalse(records.isRecycled());
	}

	@Test
	public void testRecordsWithSplitsRecycledWhenEmpty() throws Exception {
		final TestingRecordsWithSplitIds<String> records = new TestingRecordsWithSplitIds<>("test-split", "value1", "value2");
		final SourceReader<?, ?> reader = createReaderAndAwaitAvailable("test-split", records);

		// poll thrice: twice to get all records, one more to trigger recycle and moving to the next split
		reader.pollNext(new TestingReaderOutput<>());
		reader.pollNext(new TestingReaderOutput<>());
		reader.pollNext(new TestingReaderOutput<>());

		assertTrue(records.isRecycled());
	}

	// ---------------- helper methods -----------------

	@Override
	protected MockSourceReader createReader() {
		FutureCompletingBlockingQueue<RecordsWithSplitIds<int[]>> elementsQueue =
			new FutureCompletingBlockingQueue<>();
		MockSplitReader mockSplitReader =
			new MockSplitReader(2, true);
		return new MockSourceReader(
			elementsQueue,
			() -> mockSplitReader,
			getConfig(),
			null);
	}

	@Override
	protected List<MockSourceSplit> getSplits(int numSplits, int numRecordsPerSplit, Boundedness boundedness) {
		List<MockSourceSplit> mockSplits = new ArrayList<>();
		for (int i = 0; i < numSplits; i++) {
			mockSplits.add(getSplit(i, numRecordsPerSplit, boundedness));
		}
		return mockSplits;
	}

	@Override
	protected MockSourceSplit getSplit(int splitId, int numRecords, Boundedness boundedness) {
		MockSourceSplit mockSplit;
		if (boundedness == Boundedness.BOUNDED) {
			mockSplit = new MockSourceSplit(splitId, 0, numRecords);
		} else {
			mockSplit = new MockSourceSplit(splitId);
		}
		for (int j = 0; j < numRecords; j++) {
			mockSplit.addRecord(splitId * 10 + j);
		}
		return mockSplit;
	}

	@Override
	protected long getNextRecordIndex(MockSourceSplit split) {
		return split.index();
	}

	private Configuration getConfig() {
		Configuration config = new Configuration();
		config.setInteger(SourceReaderOptions.ELEMENT_QUEUE_CAPACITY, 1);
		config.setLong(SourceReaderOptions.SOURCE_READER_CLOSE_TIMEOUT, 30000L);
		return config;
	}

	// ------------------------------------------------------------------------
	//  Testing Setup Helpers
	// ------------------------------------------------------------------------

	private static <E> SourceReader<E, ?> createReaderAndAwaitAvailable(
		final String splitId,
		final RecordsWithSplitIds<E> records) throws Exception {

		final FutureCompletingBlockingQueue<RecordsWithSplitIds<E>> elementsQueue =
			new FutureCompletingBlockingQueue<>();

		final SourceReader<E, TestingSourceSplit> reader = new SingleThreadMultiplexSourceReaderBase<E, E, TestingSourceSplit, TestingSourceSplit>(
			elementsQueue,
			() -> new TestingSplitReader<E, TestingSourceSplit>(records),
			new PassThroughRecordEmitter<E, TestingSourceSplit>(),
			new Configuration(),
			new TestingReaderContext()) {

			@Override
			public void notifyCheckpointComplete(long checkpointId) throws Exception {}

			@Override
			protected void onSplitFinished(Collection<String> finishedSplitIds) {}

			@Override
			protected TestingSourceSplit initializedState(TestingSourceSplit split) {
				return split;
			}

			@Override
			protected TestingSourceSplit toSplitType(String splitId, TestingSourceSplit splitState) {
				return splitState;
			}
		};

		reader.start();

		final List<TestingSourceSplit> splits = Collections.singletonList(new TestingSourceSplit(splitId));
		reader.addSplits(splits);

		reader.isAvailable().get();

		return reader;
	}
}
