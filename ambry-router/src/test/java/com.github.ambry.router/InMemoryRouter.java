/**
 * Copyright 2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.router;

import com.github.ambry.account.Account;
import com.github.ambry.account.Container;
import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.clustermap.ClusterMapUtils;
import com.github.ambry.clustermap.PartitionId;
import com.github.ambry.commons.BlobId;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.messageformat.BlobInfo;
import com.github.ambry.messageformat.BlobProperties;
import com.github.ambry.notification.NotificationBlobType;
import com.github.ambry.notification.NotificationSystem;
import com.github.ambry.protocol.GetOption;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.ambry.router.RouterUtils.*;


/**
 * An implementation of {@link Router} that holds blobs in memory.
 */
public class InMemoryRouter implements Router {
  public final static String OPERATION_THROW_EARLY_RUNTIME_EXCEPTION = "routerThrowEarlyRuntimeException";
  public final static String OPERATION_THROW_LATE_RUNTIME_EXCEPTION = "routerThrowLateRuntimeException";
  public final static String OPERATION_THROW_ROUTER_EXCEPTION = "routerThrowRouterException";
  private final ConcurrentHashMap<String, InMemoryBlob> blobs = new ConcurrentHashMap<>();
  private final ConcurrentSkipListSet<String> deletedBlobs = new ConcurrentSkipListSet<>();
  private final AtomicBoolean routerOpen = new AtomicBoolean(true);
  private final ExecutorService operationPool;
  private final NotificationSystem notificationSystem;
  private final ClusterMap clusterMap;
  private VerifiableProperties verifiableProperties;

  /**
   * Changes the {@link VerifiableProperties} instance with the router so that the behaviour can be changed on the fly.
   * @param verifiableProperties the{@link VerifiableProperties} that will dictate behaviour.
   */
  public void setVerifiableProperties(VerifiableProperties verifiableProperties) {
    this.verifiableProperties = verifiableProperties;
  }

  /**
   * Creates an instance of InMemoryRouter.
   * @param verifiableProperties properties map that defines the behavior of this instance.
   * @param notificationSystem the notification system to use to notify creation/deletion of blobs.
   * @param clusterMap the cluster map for the cluster.
   */
  public InMemoryRouter(VerifiableProperties verifiableProperties, NotificationSystem notificationSystem,
      ClusterMap clusterMap) {
    Objects.requireNonNull(clusterMap);
    setVerifiableProperties(verifiableProperties);
    operationPool = Executors.newFixedThreadPool(1);
    this.notificationSystem = notificationSystem;
    this.clusterMap = clusterMap;
  }

  /**
   * Creates an instance of InMemoryRouter.
   * @param verifiableProperties properties map that defines the behavior of this instance.
   * @param clusterMap the cluster map for the cluster.
   */
  public InMemoryRouter(VerifiableProperties verifiableProperties, ClusterMap clusterMap) {
    this(verifiableProperties, null, clusterMap);
  }

  /**
   * Representation of a blob in memory. Contains blob properties, user metadata and blob data.
   */
  public static class InMemoryBlob {
    private final BlobProperties blobProperties;
    private final byte[] userMetadata;
    private final ByteBuffer blob;

    public InMemoryBlob(BlobProperties blobProperties, byte[] userMetadata, ByteBuffer blob) {
      this.blobProperties =
          new BlobProperties(blob.remaining(), blobProperties.getServiceId(), blobProperties.getOwnerId(),
              blobProperties.getContentType(), blobProperties.isPrivate(), blobProperties.getTimeToLiveInSeconds(),
              blobProperties.getCreationTimeInMs());
      this.userMetadata = userMetadata;
      this.blob = blob;
    }

    public BlobProperties getBlobProperties() {
      return blobProperties;
    }

    public byte[] getUserMetadata() {
      return userMetadata;
    }

    /**
     * @return the entire blob as a {@link ByteBuffer}
     */
    public ByteBuffer getBlob() {
      return ByteBuffer.wrap(blob.array());
    }

    /**
     * @param range the {@link ByteRange} for the blob, or null.
     * @return the blob content within the provided range, or the entire blob, if the range is null.
     * @throws RouterException if the range was non-null, but could not be resolved.
     */
    public ByteBuffer getBlob(ByteRange range) throws RouterException {
      ByteBuffer buf;
      if (range == null) {
        buf = getBlob();
      } else {
        ByteRange resolvedRange;
        try {
          resolvedRange = range.toResolvedByteRange(blob.array().length);
        } catch (IllegalArgumentException e) {
          throw new RouterException("Invalid range for blob", e, RouterErrorCode.RangeNotSatisfiable);
        }
        buf = ByteBuffer.wrap(blob.array(), (int) resolvedRange.getStartOffset(), (int) resolvedRange.getRangeSize());
      }
      return buf;
    }
  }

  @Override
  public Future<GetBlobResult> getBlob(String blobId, GetBlobOptions options) {
    return getBlob(blobId, options, null);
  }

  @Override
  public Future<GetBlobResult> getBlob(String blobId, GetBlobOptions options, Callback<GetBlobResult> callback) {
    FutureResult<GetBlobResult> futureResult = new FutureResult<>();
    handlePrechecks(futureResult, callback);
    ReadableStreamChannel blobDataChannel = null;
    BlobInfo blobInfo = null;
    Exception exception = null;
    try {
      getBlobIdFromString(blobId, clusterMap);
      if (deletedBlobs.contains(blobId) && !options.getGetOption().equals(GetOption.Include_All)
          && !options.getGetOption().equals(GetOption.Include_Deleted_Blobs)) {
        exception = new RouterException("Blob deleted", RouterErrorCode.BlobDeleted);
      } else if (!blobs.containsKey(blobId)) {
        exception = new RouterException("Blob not found", RouterErrorCode.BlobDoesNotExist);
      } else {
        InMemoryBlob blob = blobs.get(blobId);
        switch (options.getOperationType()) {
          case Data:
            blobDataChannel = new ByteBufferRSC(blob.getBlob(options.getRange()));
            break;
          case BlobInfo:
            blobInfo = new BlobInfo(blob.getBlobProperties(), blob.getUserMetadata());
            break;
          case All:
            blobDataChannel = new ByteBufferRSC(blob.getBlob(options.getRange()));
            blobInfo = new BlobInfo(blob.getBlobProperties(), blob.getUserMetadata());
            break;
        }
      }
    } catch (RouterException e) {
      exception = e;
    } catch (Exception e) {
      exception = new RouterException(e, RouterErrorCode.UnexpectedInternalError);
    } finally {
      GetBlobResult operationResult = exception == null ? new GetBlobResult(blobInfo, blobDataChannel) : null;
      completeOperation(futureResult, callback, operationResult, exception);
    }
    return futureResult;
  }

  @Override
  public Future<String> putBlob(BlobProperties blobProperties, byte[] usermetadata, ReadableStreamChannel channel) {
    return putBlob(blobProperties, usermetadata, channel, null);
  }

  @Override
  public Future<String> putBlob(BlobProperties blobProperties, byte[] usermetadata, ReadableStreamChannel channel,
      Callback<String> callback) {
    FutureResult<String> futureResult = new FutureResult<>();
    handlePrechecks(futureResult, callback);
    PostData postData = new PostData(blobProperties, usermetadata, channel, futureResult, callback);
    operationPool.submit(new InMemoryBlobPoster(postData, blobs, notificationSystem, clusterMap));
    return futureResult;
  }

  @Override
  public Future<Void> deleteBlob(String blobId, String serviceId) {
    return deleteBlob(blobId, serviceId, null);
  }

  @Override
  public Future<Void> deleteBlob(String blobId, String serviceId, Callback<Void> callback) {
    FutureResult<Void> futureResult = new FutureResult<>();
    handlePrechecks(futureResult, callback);
    Exception exception = null;
    try {
      getBlobIdFromString(blobId, clusterMap);
      if (!deletedBlobs.contains(blobId) && blobs.containsKey(blobId)) {
        deletedBlobs.add(blobId);
        if (notificationSystem != null) {
          notificationSystem.onBlobDeleted(blobId, serviceId);
        }
      } else if (!deletedBlobs.contains(blobId)) {
        exception = new RouterException("Blob not found", RouterErrorCode.BlobDoesNotExist);
      }
    } catch (RouterException e) {
      exception = e;
    } catch (Exception e) {
      exception = new RouterException(e, RouterErrorCode.UnexpectedInternalError);
    } finally {
      completeOperation(futureResult, callback, null, exception);
    }
    return futureResult;
  }

  @Override
  public void close() throws IOException {
    try {
      if (routerOpen.compareAndSet(true, false)) {
        operationPool.shutdown();
        operationPool.awaitTermination(1, TimeUnit.MINUTES);
      } else {
        operationPool.awaitTermination(1, TimeUnit.MINUTES);
      }
    } catch (InterruptedException e) {
      // too bad.
    }
  }

  /**
   * Gets all the blobs that are "active" (not deleted).
   * @return a map of all blobs that are active.
   */
  public Map<String, InMemoryBlob> getActiveBlobs() {
    return Collections.unmodifiableMap(blobs);
  }

  /**
   * Gets the set of ids of blobs that have been deleted.
   * @return the set of ids of blobs that have been deleted.
   */
  public Set<String> getDeletedBlobs() {
    return Collections.unmodifiableSet(deletedBlobs);
  }

  /**
   * Does pre checks and throws exceptions if necessary or requested for.
   * @param futureResult the {@link FutureResult} to update in case the operation has to be completed.
   * @param callback the {@link Callback} that needs to be invoked in case the operation has to be completed. Can be
   *                 null.
   */
  private void handlePrechecks(FutureResult futureResult, Callback callback) {
    if (!routerOpen.get()) {
      completeOperation(futureResult, callback, null,
          new RouterException("Cannot accept operation because Router is closed", RouterErrorCode.RouterClosed));
    } else if (verifiableProperties.containsKey(OPERATION_THROW_EARLY_RUNTIME_EXCEPTION)) {
      throw new RuntimeException(OPERATION_THROW_EARLY_RUNTIME_EXCEPTION);
    } else if (verifiableProperties.containsKey(OPERATION_THROW_LATE_RUNTIME_EXCEPTION)) {
      completeOperation(futureResult, callback, null, new RuntimeException(OPERATION_THROW_LATE_RUNTIME_EXCEPTION));
    } else if (verifiableProperties.containsKey(OPERATION_THROW_ROUTER_EXCEPTION)) {
      RouterErrorCode errorCode = RouterErrorCode.UnexpectedInternalError;
      try {
        errorCode = RouterErrorCode.valueOf(verifiableProperties.getString(OPERATION_THROW_ROUTER_EXCEPTION));
      } catch (IllegalArgumentException e) {
        // it's alright.
      }
      RouterException routerException = new RouterException(OPERATION_THROW_ROUTER_EXCEPTION, errorCode);
      completeOperation(futureResult, callback, null, routerException);
    }
  }

  /**
   * Completes a router operation by invoking the {@code callback} and setting the {@code futureResult} with
   * {@code operationResult} (if any) and {@code exception} (if any).
   * @param futureResult the {@link FutureResult} that needs to be set.
   * @param callback the {@link Callback} that needs to be invoked. Can be null.
   * @param operationResult the result of the operation (if any).
   * @param exception {@link Exception} encountered while performing the operation (if any).
   */
  protected static void completeOperation(FutureResult futureResult, Callback callback, Object operationResult,
      Exception exception) {
    futureResult.done(operationResult, exception);
    if (callback != null) {
      callback.onCompletion(operationResult, exception);
    }
  }
}

/**
 * Thread to read the post data async and store it.
 */
class InMemoryBlobPoster implements Runnable {
  private final PostData postData;
  private final ConcurrentHashMap<String, InMemoryRouter.InMemoryBlob> blobs;
  private final NotificationSystem notificationSystem;
  private final ClusterMap clusterMap;

  /**
   * Create a new instance.
   * @param postData the data that came with the POST request as {@link PostData}.
   * @param blobs the list of blobs in memory.
   * @param notificationSystem the notification system to use to notify creation/deletion of blobs.
   * @param clusterMap the cluster map for the cluster.
   */
  public InMemoryBlobPoster(PostData postData, ConcurrentHashMap<String, InMemoryRouter.InMemoryBlob> blobs,
      NotificationSystem notificationSystem, ClusterMap clusterMap) {
    this.postData = postData;
    this.blobs = blobs;
    this.notificationSystem = notificationSystem;
    this.clusterMap = clusterMap;
  }

  @Override
  public void run() {
    String operationResult = null;
    Exception exception = null;
    try {
      String blobId = new BlobId(BlobId.DEFAULT_FLAG, ClusterMapUtils.UNKNOWN_DATACENTER_ID, Account.UNKNOWN_ACCOUNT_ID,
          Container.UNKNOWN_CONTAINER_ID, getPartitionForPut()).getID();
      if (blobs.containsKey(blobId)) {
        exception = new RouterException("Blob ID duplicate created.", RouterErrorCode.UnexpectedInternalError);
      }
      ByteBuffer blobData = readBlob(postData.getReadableStreamChannel());
      InMemoryRouter.InMemoryBlob blob =
          new InMemoryRouter.InMemoryBlob(postData.getBlobProperties(), postData.getUsermetadata(), blobData);
      blobs.put(blobId, blob);
      if (notificationSystem != null) {
        notificationSystem.onBlobCreated(blobId, postData.getBlobProperties(), postData.getUsermetadata(),
            NotificationBlobType.Simple);
      }
      operationResult = blobId;
    } catch (RouterException e) {
      exception = e;
    } catch (Exception e) {
      exception = new RouterException(e, RouterErrorCode.UnexpectedInternalError);
    } finally {
      InMemoryRouter.completeOperation(postData.getFuture(), postData.getCallback(), operationResult, exception);
    }
  }

  /**
   * Reads blob data and returns the content as a {@link ByteBuffer}.
   * @param postContent the blob data.
   * @return the blob data in a {@link ByteBuffer}.
   * @throws InterruptedException
   */
  private ByteBuffer readBlob(ReadableStreamChannel postContent) throws InterruptedException {
    ByteArrayOutputStream blobDataStream = new ByteArrayOutputStream();
    ByteBufferAWC channel = new ByteBufferAWC();
    postContent.readInto(channel, new CloseWriteChannelCallback(channel));
    ByteBuffer chunk = channel.getNextChunk();
    IllegalStateException exception = null;
    while (chunk != null) {
      byte[] chunkData = new byte[chunk.remaining()];
      chunk.get(chunkData);
      blobDataStream.write(chunkData, 0, chunkData.length);
      channel.resolveOldestChunk(exception);
      if (exception != null) {
        channel.close();
        throw exception;
      } else {
        chunk = channel.getNextChunk();
      }
    }
    return ByteBuffer.wrap(blobDataStream.toByteArray());
  }

  /**
   * Choose a random {@link PartitionId} and return it. This code is partially copied from
   * {@code PutOperation#getPartitionForPut}.
   * @return the chosen {@link PartitionId}
   * @throws RouterException
   */
  private PartitionId getPartitionForPut() throws RouterException {
    List<? extends PartitionId> partitions = clusterMap.getWritablePartitionIds();
    if (partitions.isEmpty()) {
      throw new RouterException("No writable partitions available.", RouterErrorCode.AmbryUnavailable);
    }
    return partitions.get(ThreadLocalRandom.current().nextInt(partitions.size()));
  }
}

/**
 * Data that comes with the POST request. Contains blob properties, user metadata and blob data. Also has the
 * future and callback that need to be invoked on operation completion.
 */
class PostData {
  private final BlobProperties blobProperties;
  private final byte[] usermetadata;
  private final ReadableStreamChannel readableStreamChannel;
  private final FutureResult<String> future;
  private final Callback<String> callback;

  public BlobProperties getBlobProperties() {
    return blobProperties;
  }

  public byte[] getUsermetadata() {
    return usermetadata;
  }

  public ReadableStreamChannel getReadableStreamChannel() {
    return readableStreamChannel;
  }

  public FutureResult<String> getFuture() {
    return future;
  }

  public Callback<String> getCallback() {
    return callback;
  }

  public PostData(BlobProperties blobProperties, byte[] usermetadata, ReadableStreamChannel readableStreamChannel,
      FutureResult<String> future, Callback<String> callback) {
    this.blobProperties = blobProperties;
    this.usermetadata = usermetadata;
    this.readableStreamChannel = readableStreamChannel;
    this.future = future;
    this.callback = callback;
  }
}

/**
 * Callback for {@link ByteBufferAWC} that closes the channel on {@link #onCompletion(Long, Exception)}.
 */
class CloseWriteChannelCallback implements Callback<Long> {
  private final ByteBufferAWC channel;

  /**
   * Creates a callback to close {@code channel} on {@link #onCompletion(Long, Exception)}.
   * @param channel the {@link ByteBufferAWC} that needs to be closed.
   */
  public CloseWriteChannelCallback(ByteBufferAWC channel) {
    this.channel = channel;
  }

  @Override
  public void onCompletion(Long result, Exception exception) {
    channel.close();
  }
}
