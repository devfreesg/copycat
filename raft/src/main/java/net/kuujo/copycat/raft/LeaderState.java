/*
 * Copyright 2015 the original author or authors.
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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.raft;

import net.kuujo.copycat.io.Buffer;
import net.kuujo.copycat.io.HeapBuffer;
import net.kuujo.copycat.raft.protocol.*;
import net.kuujo.copycat.raft.storage.RaftEntry;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Leader state.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
class LeaderState extends ActiveState {
  private static final Buffer EMPTY_BUFFER = HeapBuffer.allocate(0);
  private static final int MAX_BATCH_SIZE = 1024 * 1024;
  private ScheduledFuture<?> currentTimer;
  private final Replicator replicator = new Replicator();

  public LeaderState(RaftContext context) {
    super(context);
  }

  @Override
  public RaftState.Type type() {
    return RaftState.Type.LEADER;
  }

  @Override
  public synchronized CompletableFuture<RaftState> open() {
    return super.open()
      .thenRun(this::applyEntries)
      .thenRun(replicator::commit)
      .thenRun(this::takeLeadership)
      .thenRun(this::startHeartbeatTimer)
      .thenApply(v -> this);
  }

  /**
   * Sets the current node as the cluster leader.
   */
  private void takeLeadership() {
    context.setLeader(context.getLocalMember().id());
  }

  /**
   * Applies all unapplied entries to the log.
   */
  private void applyEntries() {
    if (!context.log().isEmpty()) {
      int count = 0;
      long lastIndex = context.log().lastIndex();
      for (long commitIndex = Math.max(context.getCommitIndex(), context.log().firstIndex()); commitIndex <= lastIndex; commitIndex++) {
        context.setCommitIndex(commitIndex);
        applyEntry(commitIndex);
        count++;
      }
      LOGGER.debug("{} - Applied {} entries to log", context.getLocalMember().id(), count);
    }
  }

  /**
   * Starts heartbeating all cluster members.
   */
  private void startHeartbeatTimer() {
    // Set a timer that will be used to periodically synchronize with other nodes
    // in the cluster. This timer acts as a heartbeat to ensure this node remains
    // the leader.
    LOGGER.debug("{} - Setting heartbeat timer", context.getLocalMember().id());
    currentTimer = context.executor().scheduleAtFixedRate(this::heartbeatMembers, 0, context.getHeartbeatInterval(), TimeUnit.MILLISECONDS);
  }

  /**
   * Sends a heartbeat to all members of the cluster.
   */
  private void heartbeatMembers() {
    context.checkThread();
    if (isOpen()) {
      replicator.commit();
    }
  }

  @Override
  public CompletableFuture<PollResponse> poll(final PollRequest request) {
    return CompletableFuture.completedFuture(logResponse(PollResponse.builder()
      .withStatus(Response.Status.OK)
      .withTerm(context.getTerm())
      .withAccepted(false)
      .build()));
  }

  @Override
  public CompletableFuture<VoteResponse> vote(final VoteRequest request) {
    if (request.term() > context.getTerm()) {
      LOGGER.debug("{} - Received greater term", context.getLocalMember().id());
      transition(RaftState.Type.FOLLOWER);
      return super.vote(request);
    } else {
      return CompletableFuture.completedFuture(logResponse(VoteResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withVoted(false)
        .build()));
    }
  }

  @Override
  public CompletableFuture<AppendResponse> append(final AppendRequest request) {
    context.checkThread();
    if (request.term() > context.getTerm()) {
      return super.append(request);
    } else if (request.term() < context.getTerm()) {
      return CompletableFuture.completedFuture(logResponse(AppendResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(context.getTerm())
        .withSucceeded(false)
        .withLogIndex(context.log().lastIndex())
        .build()));
    } else {
      transition(RaftState.Type.FOLLOWER);
      return super.append(request);
    }
  }

  @Override
  public CompletableFuture<ReadResponse> read(ReadRequest request) {
    context.checkThread();
    logRequest(request);

    CompletableFuture<ReadResponse> future = new CompletableFuture<>();

    switch (request.consistency()) {
      // Consistency mode WEAK or DEFAULT is immediately evaluated and returned.
      case WEAK:
      case DEFAULT:
        RESULT.clear();
        context.commit(request.key(), request.entry(), RESULT);
        future.complete(logResponse(ReadResponse.builder()
          .withStatus(Response.Status.OK)
          .withResult(RESULT.flip())
          .build()));
        break;
      // Consistency mode STRONG requires synchronous consistency check prior to applying the read.
      case STRONG:
        LOGGER.debug("{} - Synchronizing logs to index {} for read", context.getLocalMember().id(), context.log().lastIndex());
        request.acquire();
        replicator.commit().whenComplete((index, error) -> {
          context.checkThread();
          if (isOpen()) {
            if (error == null) {
              try {
                RESULT.clear();
                context.commit(request.key(), request.entry(), RESULT);
                future.complete(logResponse(ReadResponse.builder()
                  .withStatus(Response.Status.OK)
                  .withResult(RESULT.flip())
                  .build()));
              } catch (Exception e) {
                future.complete(logResponse(ReadResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(RaftError.Type.APPLICATION_ERROR)
                  .build()));
              } finally {
                request.release();
              }
            } else {
              future.complete(logResponse(ReadResponse.builder()
                .withStatus(Response.Status.ERROR)
                .withError(RaftError.Type.READ_ERROR)
                .build()));
              request.release();
            }
          } else {
            request.release();
          }
        });
        break;
    }
    return future;
  }

  @Override
  public CompletableFuture<WriteResponse> write(final WriteRequest request) {
    context.checkThread();
    logRequest(request);

    CompletableFuture<WriteResponse> future = new CompletableFuture<>();
    final Buffer key = request.key();
    final Buffer entry = request.entry();
    final long term = context.getTerm();
    final long index;
    try (RaftEntry logEntry = context.log().createEntry()) {
      logEntry.writeType(RaftEntry.Type.COMMAND)
        .writeTerm(term)
        .writeEntry(entry);
      if (key != null)
        logEntry.writeKey(key);
      index = logEntry.index();
    }

    LOGGER.debug("{} - Appended entry to log at index {}", context.getLocalMember().id(), index);
    LOGGER.debug("{} - Replicating logs up to index {} for write", context.getLocalMember().id(), index);

    // Attempt to replicate the entry to a quorum of the cluster.
    request.acquire();
    replicator.commit(index).whenComplete((resultIndex, error) -> {
      context.checkThread();
      if (isOpen()) {
        if (error == null) {
          try {
            context.commit(request.key(), request.entry(), RESULT.clear());
            future.complete(logResponse(WriteResponse.builder()
              .withStatus(Response.Status.OK)
              .withResult(RESULT.flip())
              .build()));
          } catch (Exception e) {
            future.complete(logResponse(WriteResponse.builder()
              .withStatus(Response.Status.ERROR)
              .withError(RaftError.Type.APPLICATION_ERROR)
              .build()));
          } finally {
            context.setLastApplied(index);
            request.release();
          }
        } else {
          future.complete(logResponse(WriteResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(RaftError.Type.WRITE_ERROR)
            .build()));
          request.release();
        }
      } else {
        request.release();
      }
    });
    return future;
  }

  @Override
  public CompletableFuture<DeleteResponse> delete(final DeleteRequest request) {
    context.checkThread();
    logRequest(request);

    CompletableFuture<DeleteResponse> future = new CompletableFuture<>();
    final Buffer key = request.key();
    final long term = context.getTerm();
    final long index;
    try (RaftEntry logEntry = context.log().createEntry()) {
      logEntry.writeType(RaftEntry.Type.TOMBSTONE)
        .writeTerm(term)
        .writeKey(key)
        .writeEntry(EMPTY_BUFFER);
      index = logEntry.index();
    }

    LOGGER.debug("{} - Appended entry to log at index {}", context.getLocalMember().id(), index);
    LOGGER.debug("{} - Replicating logs up to index {} for write", context.getLocalMember().id(), index);

    // Attempt to replicate the entry to a quorum of the cluster.
    request.acquire();
    replicator.commit(index).whenComplete((resultIndex, error) -> {
      context.checkThread();
      if (isOpen()) {
        if (error == null) {
          try {
            RESULT.clear();
            context.commit(key, EMPTY_BUFFER, RESULT);
            future.complete(logResponse(DeleteResponse.builder()
              .withStatus(Response.Status.OK)
              .withResult(RESULT.flip())
              .build()));
          } catch (Exception e) {
            future.complete(logResponse(DeleteResponse.builder()
              .withStatus(Response.Status.ERROR)
              .withError(RaftError.Type.APPLICATION_ERROR)
              .build()));
          } finally {
            context.setLastApplied(index);
            request.release();
          }
        } else {
          future.complete(logResponse(DeleteResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(RaftError.Type.DELETE_ERROR)
            .build()));
          request.release();
        }
      } else {
        request.release();
      }
    });
    return future;
  }

  /**
   * Cancels the ping timer.
   */
  private void cancelPingTimer() {
    if (currentTimer != null) {
      LOGGER.debug("{} - Cancelling ping timer", context.getLocalMember().id());
      currentTimer.cancel(false);
    }
  }

  @Override
  public synchronized CompletableFuture<Void> close() {
    return super.close().thenRun(this::cancelPingTimer);
  }

  /**
   * Log replicator.
   */
  private class Replicator {
    private final List<Replica> replicas = new ArrayList<>();
    private final List<Long> commitTimes = new ArrayList<>();
    private long commitTime;
    private CompletableFuture<Void> commitFuture;
    private CompletableFuture<Void> nextCommitFuture;
    private final TreeMap<Long, CompletableFuture<Long>> commitFutures = new TreeMap<>();
    private int quorum;
    private int quorumIndex;

    private Replicator() {
      update();
    }

    /**
     * Updates the replicator's cluster configuration.
     */
    private void update() {
      Set<RaftMember> members = context.getMembers().stream()
        .filter(m -> m.id() != context.getLocalMember().id() && m.type() == RaftMember.Type.ACTIVE)
        .collect(Collectors.toSet());
      Set<Integer> ids = members.stream().map(RaftMember::id).collect(Collectors.toSet());

      Iterator<Replica> iterator = replicas.iterator();
      while (iterator.hasNext()) {
        Replica replica = iterator.next();
        if (!ids.contains(replica.member.id())) {
          iterator.remove();
          commitTimes.remove(replica.id);
        }
      }

      Set<Integer> replicas = this.replicas.stream().map(r -> r.member.id()).collect(Collectors.toSet());
      for (RaftMember member : members) {
        if (!replicas.contains(member.id())) {
          this.replicas.add(new Replica(this.replicas.size(), member));
          this.commitTimes.add(System.currentTimeMillis());
        }
      }

      this.quorum = (int) Math.floor((this.replicas.size() + 1) / 2.0);
      this.quorumIndex = quorum - 1;
    }

    /**
     * Triggers a commit.
     *
     * @return A completable future to be completed the next time entries are committed to a majority of the cluster.
     */
    private CompletableFuture<Void> commit() {
      if (commitFuture == null) {
        commitFuture = new CompletableFuture<>();
        commitTime = System.currentTimeMillis();
        replicas.forEach(Replica::commit);
        return commitFuture;
      } else if (nextCommitFuture == null) {
        nextCommitFuture = new CompletableFuture<>();
        return nextCommitFuture;
      } else {
        return nextCommitFuture;
      }
    }

    /**
     * Registers a commit handler for the given commit index.
     *
     * @param index The index for which to register the handler.
     * @return A completable future to be completed once the given log index has been committed.
     */
    private CompletableFuture<Long> commit(long index) {
      return commitFutures.computeIfAbsent(index, i -> {
        replicas.forEach(Replica::commit);
        return new CompletableFuture<>();
      });
    }

    /**
     * Sets a commit time.
     */
    private void commitTime(int id) {
      commitTimes.set(id, System.currentTimeMillis());

      // Sort the list of commit times. Use the quorum index to get the last time the majority of the cluster
      // was contacted. If the current commitFuture's time is less than the commit time then trigger the
      // commit future and reset it to the next commit future.
      Collections.sort(commitTimes);
      long commitTime = commitTimes.get(quorumIndex);
      if (commitFuture != null && this.commitTime >= commitTime) {
        commitFuture.complete(null);
        commitFuture = nextCommitFuture;
        nextCommitFuture = null;
        if (this.commitFuture != null) {
          this.commitTime = System.currentTimeMillis();
          replicas.forEach(Replica::commit);
        }
      }
    }

    /**
     * Checks whether any futures can be completed.
     */
    private void commitEntries() {
      context.checkThread();

      // Sort the list of replicas, order by the last index that was replicated
      // to the replica. This will allow us to determine the median index
      // for all known replicated entries across all cluster members.
      Collections.sort(replicas, (o1, o2) -> Long.compare(o2.matchIndex != 0 ? o2.matchIndex : 0l, o1.matchIndex != 0 ? o1.matchIndex : 0l));

      // Set the current commit index as the median replicated index.
      // Since replicas is a list with zero based indexes, use the negation of
      // the required quorum size to get the index of the replica with the least
      // possible quorum replication. That replica's match index is the commit index.
      // Set the commit index. Once the commit index has been set we can run
      // all tasks up to the given commit.
      long commitIndex = replicas.get(quorumIndex).matchIndex;
      long recycleIndex = replicas.get(0).matchIndex;
      if (commitIndex > 0) {
        context.log().commit(commitIndex);
        context.setCommitIndex(commitIndex);
        context.log().recycle(recycleIndex);
        context.setRecycleIndex(recycleIndex);
        SortedMap<Long, CompletableFuture<Long>> futures = commitFutures.headMap(commitIndex, true);
        for (Map.Entry<Long, CompletableFuture<Long>> entry : futures.entrySet()) {
          entry.getValue().complete(entry.getKey());
        }
        futures.clear();
      }
    }

    /**
     * Remote replica.
     */
    private class Replica {
      private final int id;
      private final RaftMember member;
      private long nextIndex = 1;
      private long matchIndex = 0;
      private boolean committing;

      private Replica(int id, RaftMember member) {
        this.id = id;
        this.member = member;
        this.nextIndex = Math.max(context.log().lastIndex(), 1);
      }

      /**
       * Triggers a commit for the replica.
       */
      private void commit() {
        if (!committing && isOpen()) {
          // If the log is empty then send an empty commit.
          // If the next index hasn't yet been set then we send an empty commit first.
          // If the next index is greater than the last index then send an empty commit.
          if (context.log().isEmpty() || nextIndex > context.log().lastIndex()) {
            emptyCommit();
          } else {
            entriesCommit();
          }
        }
      }

      /**
       * Gets the previous index.
       */
      private long getPrevIndex() {
        return nextIndex - 1;
      }

      /**
       * Gets the previous entry.
       */
      private RaftEntry getPrevEntry(long prevIndex) {
        if (context.log().containsIndex(prevIndex)) {
          return context.log().getEntry(prevIndex);
        }
        return null;
      }

      /**
       * Gets a list of entries to send.
       */
      @SuppressWarnings("unchecked")
      private List<RaftEntry> getEntries(long prevIndex) {
        long index;
        if (context.log().isEmpty()) {
          return Collections.EMPTY_LIST;
        } else if (prevIndex != 0) {
          index = prevIndex + 1;
        } else {
          index = context.log().firstIndex();
        }

        List<RaftEntry> entries = new ArrayList<>(1024);
        int size = 0;
        while (size < MAX_BATCH_SIZE && index <= context.log().lastIndex()) {
          RaftEntry entry = context.log().getEntry(index);
          size += entry.size();
          entries.add(entry);
          index++;
        }
        return entries;
      }

      /**
       * Performs an empty commit.
       */
      @SuppressWarnings("unchecked")
      private void emptyCommit() {
        long prevIndex = getPrevIndex();
        RaftEntry prevEntry = getPrevEntry(prevIndex);
        commit(prevIndex, prevEntry, Collections.EMPTY_LIST);
      }

      /**
       * Performs a commit with entries.
       */
      private void entriesCommit() {
        long prevIndex = getPrevIndex();
        RaftEntry prevEntry = getPrevEntry(prevIndex);
        List<RaftEntry> entries = getEntries(prevIndex);
        commit(prevIndex, prevEntry, entries);
      }

      /**
       * Sends a commit message.
       */
      private void commit(long prevIndex, RaftEntry prevEntry, List<RaftEntry> entries) {
        AppendRequest request = AppendRequest.builder()
          .withTerm(context.getTerm())
          .withLeader(context.getLocalMember().id())
          .withLogIndex(prevIndex)
          .withLogTerm(prevEntry != null ? prevEntry.readTerm() : 0)
          .withEntries(entries)
          .withCommitIndex(context.getCommitIndex())
          .build();

        committing = true;
        LOGGER.debug("{} - Sent {} to {}", context.getLocalMember().id(), request, member);
        context.<AppendRequest, AppendResponse>sendRequest(request, member).whenCompleteAsync((response, error) -> {
          committing = false;
          context.checkThread();

          if (isOpen()) {
            if (error == null) {
              LOGGER.debug("{} - Received {} from {}", context.getLocalMember().id(), response, member);
              if (response.status() == Response.Status.OK) {
                // Update the commit time for the replica. This will cause heartbeat futures to be triggered.
                commitTime(id);

                // If replication succeeded then trigger commit futures.
                if (response.succeeded()) {
                  updateMatchIndex(response);
                  updateNextIndex();

                  // If entries were committed to the replica then check commit indexes.
                  if (!entries.isEmpty()) {
                    commitEntries();
                  }

                  // If there are more entries to send then attempt to send another commit.
                  if (hasMoreEntries()) {
                    commit();
                  }
                } else if (response.term() > context.getTerm()) {
                  transition(RaftState.Type.FOLLOWER);
                } else {
                  resetMatchIndex(response);
                  resetNextIndex();

                  // If there are more entries to send then attempt to send another commit.
                  if (hasMoreEntries()) {
                    commit();
                  }
                }
              } else if (response.term() > context.getTerm()) {
                LOGGER.debug("{} - Received higher term from {}", context.getLocalMember().id(), member);
                transition(RaftState.Type.FOLLOWER);
              } else {
                LOGGER.warn("{} - {}", context.getLocalMember().id(), response.error() != null ? response.error() : "");
              }
            } else {
              LOGGER.warn("{} - {}", context.getLocalMember().id(), error.getMessage());
            }
          }
          request.release();
        }, context.executor());
      }

      /**
       * Returns a boolean value indicating whether there are more entries to send.
       */
      private boolean hasMoreEntries() {
        return nextIndex < context.log().lastIndex();
      }

      /**
       * Updates the match index when a response is received.
       */
      private void updateMatchIndex(AppendResponse response) {
        // If the replica returned a valid match index then update the existing match index. Because the
        // replicator pipelines replication, we perform a MAX(matchIndex, logIndex) to get the true match index.
        matchIndex = Math.max(matchIndex, response.logIndex());
      }

      /**
       * Updates the next index when the match index is updated.
       */
      private void updateNextIndex() {
        // If the match index was set, update the next index to be greater than the match index if necessary.
        // Note that because of pipelining append requests, the next index can potentially be much larger than
        // the match index. We rely on the algorithm to reject invalid append requests.
        nextIndex = Math.max(nextIndex, Math.max(matchIndex + 1, 1));
      }

      /**
       * Resets the match index when a response fails.
       */
      private void resetMatchIndex(AppendResponse response) {
        if (matchIndex == 0) {
          matchIndex = response.logIndex();
        } else if (response.logIndex() != 0) {
          matchIndex = Math.max(matchIndex, response.logIndex());
        }
        LOGGER.debug("{} - Reset match index for {} to {}", context.getLocalMember().id(), member, matchIndex);
      }

      /**
       * Resets the next index when a response fails.
       */
      private void resetNextIndex() {
        if (matchIndex != 0) {
          nextIndex = matchIndex + 1;
        } else {
          nextIndex = context.log().firstIndex();
        }
        LOGGER.debug("{} - Reset next index for {} to {}", context.getLocalMember().id(), member, nextIndex);
      }

    }
  }

}