/*
 * Copyright (c) 2017-2018 Aion foundation.
 *
 * This file is part of the aion network project.
 *
 * The aion network project is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * The aion network project is distributed in the hope that it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the aion network project source files.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * The aion network project leverages useful source code from other
 * open source projects. We greatly appreciate the effort that was
 * invested in these projects and we thank the individual contributors
 * for their work. For provenance information and contributors
 * please see <https://github.com/aionnetwork/aion/wiki/Contributors>.
 *
 * Contributors to the aion source files in decreasing order of code volume:
 * Aion foundation.
 */

package org.aion.zero.impl.sync;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.aion.base.util.ByteArrayWrapper;
import org.aion.mcf.core.ImportResult;
import org.aion.zero.impl.AionBlockchainImpl;
import org.aion.zero.impl.sync.PeerState.Mode;
import org.aion.zero.impl.types.AionBlock;
import org.slf4j.Logger;

/**
 * handle process of importing blocks to repo
 *
 * <p>TODO: targeted send
 *
 * @author chris
 */
final class TaskImportBlocks implements Runnable {

    private final AionBlockchainImpl chain;

    private final AtomicBoolean start;

    private final BlockingQueue<BlocksWrapper> downloadedBlocks;

    private final SyncStatics statis;

    private final Map<ByteArrayWrapper, Object> importedBlockHashes;

    private final Map<Integer, PeerState> peerStates;

    private final Logger log;

    TaskImportBlocks(
            final AionBlockchainImpl _chain,
            final AtomicBoolean _start,
            final SyncStatics _statis,
            final BlockingQueue<BlocksWrapper> downloadedBlocks,
            final Map<ByteArrayWrapper, Object> importedBlockHashes,
            final Map<Integer, PeerState> peerStates,
            final Logger log) {
        this.chain = _chain;
        this.start = _start;
        this.statis = _statis;
        this.downloadedBlocks = downloadedBlocks;
        this.importedBlockHashes = importedBlockHashes;
        this.peerStates = peerStates;
        this.log = log;
    }

    @Override
    public void run() {
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        while (start.get()) {

            BlocksWrapper bw;
            try {
                bw = downloadedBlocks.take();
            } catch (InterruptedException ex) {
                return;
            }

            List<AionBlock> batch = bw.getBlocks().stream()
                .filter(b -> importedBlockHashes.get(ByteArrayWrapper.wrap(b.getHash())) == null)
                .filter(b -> !chain.isPruneRestricted(b.getNumber())).collect(Collectors.toList());

            PeerState state = peerStates.get(bw.getNodeIdHash());
            if (state == null) {
                log.warn("Peer {} sent blocks that were not requested.", bw.getDisplayId());
                // ignoring these blocks
                continue;
            }

            ImportResult importResult = ImportResult.IMPORTED_NOT_BEST;

            // importing last block in batch to see if we can skip batch
            if (state != null && state.getMode() == Mode.FORWARD && !batch.isEmpty()) {
                AionBlock b = batch.get(batch.size() - 1);

                try {
                    importResult = importBlock(b, bw.getDisplayId(), state);
                } catch (Throwable e) {
                    log.error("<import-block throw> {}", e.toString());
                    if (e.getMessage() != null
                            && e.getMessage().contains("No space left on device")) {
                        log.error("Shutdown due to lack of disk space.");
                        System.exit(0);
                    }
                    continue;
                }

                switch (importResult) {
                    case IMPORTED_BEST:
                    case IMPORTED_NOT_BEST:
                    case EXIST:
                        {
                            importedBlockHashes.put(ByteArrayWrapper.wrap(b.getHash()), true);

                            long lastBlock = batch.get(batch.size() - 1).getNumber();

                            forwardModeUpdate(state, lastBlock, importResult, b.getNumber());

                            // since last import worked skipping the batch
                            batch.clear();
                            log.info("Forward skip.");
                            break;
                        }
                    default:
                        break;
                }
            }

            // remembering imported range
            long first = -1L, last;

            for (AionBlock b : batch) {
                try {
                    importResult = importBlock(b, bw.getDisplayId(), state);
                } catch (Throwable e) {
                    log.error("<import-block throw> {}", e.toString());
                    if (e.getMessage() != null
                            && e.getMessage().contains("No space left on device")) {
                        log.error("Shutdown due to lack of disk space.");
                        System.exit(0);
                    }
                    continue;
                }

                switch (importResult) {
                    case IMPORTED_BEST:
                    case IMPORTED_NOT_BEST:
                    case EXIST:
                        importedBlockHashes.put(ByteArrayWrapper.wrap(b.getHash()), true);
                        break;
                    default:
                        break;
                }

                // decide whether to change mode based on the first
                if (b == batch.get(0) && state != null) {
                    first = b.getNumber();
                    Mode mode = state.getMode();

                    switch (importResult) {
                        case IMPORTED_BEST:
                        case IMPORTED_NOT_BEST:
                        case EXIST:
                            // assuming the remaining blocks will be imported. if not, the state
                            // and base will be corrected by the next cycle
                            long lastBlock = batch.get(batch.size() - 1).getNumber();

                            if (mode == Mode.BACKWARD) {
                                // we found the fork point
                                state.setMode(Mode.FORWARD);
                                state.setBase(lastBlock);
                                state.resetRepeated();
                            } else if (mode == Mode.FORWARD) {
                                forwardModeUpdate(state, lastBlock, importResult, b.getNumber());
                            }
                            break;
                        case NO_PARENT:
                            if (mode == Mode.BACKWARD) {
                                // update base
                                state.setBase(b.getNumber());
                            } else {
                                if (mode == Mode.TORRENT) {
                                    state.setBase(chain.nextBase(state.getBase()));
                                } else {
                                    // switch to backward mode
                                    state.setMode(Mode.BACKWARD);
                                    state.setBase(b.getNumber());
                                }
                            }
                            break;
                    }
                }

                // if any block results in NO_PARENT, all subsequent blocks will too
                if (importResult == ImportResult.NO_PARENT) {
                    log.debug("Stopped importing batch due to NO_PARENT result.");
                    // store batch for later use
                    log.debug(
                            "Attempting to store batch of {} blocks starting from hash = {}, number = {}.",
                            batch.size(),
                            b.getShortHash(),
                            b.getNumber());
                    int stored = chain.storePendingBlockRange(batch);
                    log.debug("From the batch above, {} blocks were stored.", stored);
                    break;
                }
            }

            if (state != null
                    && state.getMode() == Mode.FORWARD
                    && importResult == ImportResult.EXIST) {
                // increment the repeat count every time
                // we finish a batch of imports with EXIST
                state.incRepeated();
            }

            if (state != null) {
                state.resetLastHeaderRequest(); // so we can continue immediately
            }

            last = first + batch.size() + 1;

            // TODO: repeat if any blocks were imported
            // check for stored blocks
            if (state.getMode() != Mode.BACKWARD && first > 0 && last > 0) {
                List<AionBlock> batchFromDisk = chain.loadPendingBlockRange(first, last);
                log.debug(
                        "Loaded {} blocks from disk from levels {} to {} before filtering.",
                        batchFromDisk.size(),
                        first,
                        last);

                batchFromDisk = batchFromDisk.stream()
                    .filter(b -> importedBlockHashes.get(ByteArrayWrapper.wrap(b.getHash())) == null)
                    .collect(Collectors.toList());

                if (batchFromDisk.size() > 0) {
                    log.debug("Importing {} blocks from pending storage.", batchFromDisk.size());
                } else {
                    log.debug("No blocks left after filtering imported.");
                }

                for (AionBlock b : batchFromDisk) {
                    try {
                        importResult = importBlock(b, "PENDING_STORAGE", state);
                        // TODO: delete block from storage
                    } catch (Throwable e) {
                        log.error("<import-block throw> {}", e.toString());
                        if (e.getMessage() != null
                                && e.getMessage().contains("No space left on device")) {
                            log.error("Shutdown due to lack of disk space.");
                            System.exit(0);
                        }
                        continue;
                    }

                    switch (importResult) {
                        case IMPORTED_BEST:
                            // TODO: switch to NORMAL if in FORWARD
                        case IMPORTED_NOT_BEST:
                        case EXIST:
                            importedBlockHashes.put(ByteArrayWrapper.wrap(b.getHash()), true);
                            break;
                        default:
                            break;
                    }
                }
            }

            this.statis.update(this.chain.getBestBlock().getNumber());
        }
    }

    private ImportResult importBlock(AionBlock b, String displayId, PeerState state) {
        ImportResult importResult;
        long t1 = System.currentTimeMillis();
        importResult = this.chain.tryToConnect(b);
        long t2 = System.currentTimeMillis();
        log.info(
                "<import-status: node = {}, sync mode = {}, hash = {}, number = {}, txs = {}, result = {}, time elapsed = {} ms>",
                displayId,
                (state != null ? state.getMode() : Mode.NORMAL),
                b.getShortHash(),
                b.getNumber(),
                b.getTransactionsList().size(),
                importResult,
                t2 - t1);
        return importResult;
    }

    private void forwardModeUpdate(
            PeerState state, long lastBlock, ImportResult importResult, long blockNumber) {
        // continue
        state.setBase(lastBlock);
        // if the imported best block, switch back to normal mode
        if (importResult == ImportResult.IMPORTED_BEST) {
            state.setMode(Mode.NORMAL);
            // switch peers to NORMAL otherwise they may never switch back
            for (PeerState peerState : peerStates.values()) {
                if (peerState.getMode() != Mode.NORMAL) {
                    peerState.setMode(Mode.NORMAL);
                    peerState.setBase(blockNumber);
                    peerState.resetLastHeaderRequest();
                }
            }
        }
        // if the maximum number of repeats is passed
        // then the peer is stuck endlessly importing old blocks
        // otherwise it would have found an IMPORTED block already
        if (state.getRepeated() >= state.getMaxRepeats()) {
            state.setMode(Mode.NORMAL);
            state.setBase(chain.getBestBlock().getNumber());
            state.resetLastHeaderRequest();
        }
    }
}
