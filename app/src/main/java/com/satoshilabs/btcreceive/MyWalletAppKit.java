/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.satoshilabs.btcreceive;

import android.annotation.SuppressLint;
import com.google.bitcoin.core.*;
import com.google.bitcoin.crypto.KeyCrypter;
import com.google.bitcoin.net.discovery.DnsDiscovery;
import com.google.bitcoin.store.BlockStoreException;
import com.google.bitcoin.store.SPVBlockStore;
import com.google.bitcoin.store.WalletProtobufSerializer;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * <p>Utility class that wraps the boilerplate needed to set up a new SPV bitcoinj app. Instantiate it with a directory
 * and file prefix, optionally configure a few things, then use start or startAndWait. The object will construct and
 * configure a {@link BlockChain}, {@link SPVBlockStore}, {@link Wallet} and {@link PeerGroup}. Depending on the value
 * of the blockingStartup property, startup will be considered complete once the block chain has fully synchronized,
 * so it can take a while.</p>
 *
 * <p>To add listeners and modify the objects that are constructed, you can either do that by overriding the
 * {@link #onSetupCompleted()} method (which will run on a background thread) and make your changes there,
 * or by waiting for the service to start and then accessing the objects from wherever you want. However, you cannot
 * access the objects this class creates until startup is complete.</p>
 *
 * <p>The asynchronous design of this class may seem puzzling (just use {@link #startAndWait()} if you don't want that).
 * It is to make it easier to fit bitcoinj into GUI apps, which require a high degree of responsiveness on their main
 * thread which handles all the animation and user interaction. Even when blockingStart is false, initializing bitcoinj
 * means doing potentially blocking file IO, generating keys and other potentially intensive operations. By running it
 * on a background thread, there's no risk of accidentally causing UI lag.</p>
 *
 * <p>Note that {@link #startAndWait()} can throw an unchecked {@link com.google.common.util.concurrent.UncheckedExecutionException}
 * if anything goes wrong during startup - you should probably handle it and use {@link Exception#getCause()} to figure
 * out what went wrong more precisely. Same thing if you use the async start() method.</p>
 */
public class MyWalletAppKit extends AbstractIdleService {

    private static Logger mLogger =
        LoggerFactory.getLogger(MyWalletAppKit.class);

    private final String filePrefix;
    private final NetworkParameters params;
    private volatile BlockChain vChain;
    private volatile SPVBlockStore vStore;
    private volatile Wallet vWallet;
    private volatile PeerGroup vPeerGroup;

    private final File directory;
    private volatile File vWalletFile;

    private boolean useAutoSave = true;
    private PeerAddress[] peerAddresses;
    private MyDownloadListener downloadListener;
    private boolean autoStop = true;
    private InputStream checkpoints;
    private boolean blockingStartup = true;
    private String userAgent, version;
    private final KeyCrypter keyCrypter;

    private final long scanTime;

    public MyWalletAppKit(NetworkParameters params, File directory, String filePrefix, KeyCrypter keyCrypter, long scanTime) {
        this.params = checkNotNull(params);
        this.directory = checkNotNull(directory);
        this.filePrefix = checkNotNull(filePrefix);
        this.keyCrypter = keyCrypter;
        this.scanTime = scanTime;
    }

    /** Will only connect to the given addresses. Cannot be called after startup. */
    public MyWalletAppKit setPeerNodes(PeerAddress... addresses) {
        checkState(state() == State.NEW, "Cannot call after startup");
        this.peerAddresses = addresses;
        return this;
    }

    /** Will only connect to localhost. Cannot be called after startup. */
    public MyWalletAppKit connectToLocalHost() {
        try {
            final InetAddress localHost = InetAddress.getLocalHost();
            return setPeerNodes(new PeerAddress(localHost, params.getPort()));
        } catch (UnknownHostException e) {
            // Borked machine with no loopback adapter configured properly.
            throw new RuntimeException(e);
        }
    }

    /** If true, the wallet will save itself to disk automatically whenever it changes. */
    public MyWalletAppKit setAutoSave(boolean value) {
        checkState(state() == State.NEW, "Cannot call after startup");
        useAutoSave = value;
        return this;
    }

    /**
     * If you want to learn about the sync process, you can provide a listener here. For instance, a
     * {@link DownloadListener} is a good choice.
     */
    public MyWalletAppKit setDownloadListener(MyDownloadListener listener) {
        this.downloadListener = listener;
        return this;
    }

    /** If true, will register a shutdown hook to stop the library. Defaults to true. */
    public MyWalletAppKit setAutoStop(boolean autoStop) {
        this.autoStop = autoStop;
        return this;
    }

    /**
     * If set, the file is expected to contain a checkpoints file calculated with BuildCheckpoints. It makes initial
     * block sync faster for new users - please refer to the documentation on the bitcoinj website for further details.
     */
    public MyWalletAppKit setCheckpoints(InputStream checkpoints) {
        this.checkpoints = checkNotNull(checkpoints);
        return this;
    }

    /**
     * If true (the default) then the startup of this service won't be considered complete until the network has been
     * brought up, peer connections established and the block chain synchronised. Therefore {@link #startAndWait()} can
     * potentially take a very long time. If false, then startup is considered complete once the network activity
     * begins and peer connections/block chain sync will continue in the background.
     */
    public MyWalletAppKit setBlockingStartup(boolean blockingStartup) {
        this.blockingStartup = blockingStartup;
        return this;
    }

    /**
     * Sets the string that will appear in the subver field of the version message.
     * @param userAgent A short string that should be the name of your app, e.g. "My Wallet"
     * @param version A short string that contains the version number, e.g. "1.0-BETA"
     */
    public MyWalletAppKit setUserAgent(String userAgent, String version) {
        this.userAgent = checkNotNull(userAgent);
        this.version = checkNotNull(version);
        return this;
    }

    /**
     * <p>Override this to load all wallet extensions if any are necessary.</p>
     *
     * <p>When this is called, chain(), store(), and peerGroup() will return the created objects, however they are not
     * initialized/started</p>
     */
    protected void addWalletExtensions() throws Exception { }

    /**
     * This method is invoked on a background thread after all objects are initialised, but before the peer group
     * or block chain download is started. You can tweak the objects configuration here.
     */
    protected void onSetupCompleted() { }

    @SuppressLint("NewApi")
	@Override
    protected void startUp() throws Exception {
        // Runs in a separate thread.
        if (!directory.exists()) {
            if (!directory.mkdir()) {
                throw new IOException("Could not create named directory.");
            }
        }
        FileInputStream walletStream = null;
        try {
            File chainFile = new File(directory, filePrefix + ".spvchain");
            boolean chainFileExists = chainFile.exists();
            vWalletFile = new File(directory, filePrefix + ".wallet");
            boolean shouldReplayWallet = vWalletFile.exists() && !chainFileExists;

            vStore = new SPVBlockStore(params, chainFile);
            if (!chainFileExists && checkpoints != null) {
                mLogger.info(String.format("checkpoint at time %d", scanTime));
                CheckpointManager.checkpoint(params, checkpoints, vStore, scanTime);
            }
            vChain = new BlockChain(params, vStore);
            vPeerGroup = new PeerGroup(params, vChain);
            if (this.userAgent != null)
                vPeerGroup.setUserAgent(userAgent, version);
            if (vWalletFile.exists()) {
                walletStream = new FileInputStream(vWalletFile);
                vWallet = new Wallet(params);
                addWalletExtensions(); // All extensions must be present before we deserialize
                new WalletProtobufSerializer().readWallet(WalletProtobufSerializer.parseToProto(walletStream), vWallet);
                if (shouldReplayWallet)
                    vWallet.clearTransactions(0);
            } else {
                if (keyCrypter == null)
                    vWallet = new Wallet(params);
                else
                    vWallet = new Wallet(params, keyCrypter);
                // vWallet.addKey(new ECKey());
                addWalletExtensions();
            }
            if (useAutoSave) vWallet.autosaveToFile(vWalletFile, 1, TimeUnit.SECONDS, null);
            // Set up peer addresses or discovery first, so if wallet extensions try to broadcast a transaction
            // before we're actually connected the broadcast waits for an appropriate number of connections.
            if (peerAddresses != null) {
                for (PeerAddress addr : peerAddresses) vPeerGroup.addAddress(addr);
                peerAddresses = null;
            } else {
                vPeerGroup.addPeerDiscovery(new DnsDiscovery(params));
            }
            vChain.addWallet(vWallet);
            vPeerGroup.addWallet(vWallet);
            onSetupCompleted();

            if (blockingStartup) {
                vPeerGroup.startAndWait();
                // Make sure we shut down cleanly.
                installShutdownHook();
                MyDownloadListener listener = (this.downloadListener != null) ? this.downloadListener : new MyDownloadListener();
                vPeerGroup.startBlockChainDownload(listener);
                listener.await();
            } else {
                Futures.addCallback(vPeerGroup.start(), new FutureCallback<State>() {
                    @Override
                    public void onSuccess(State result) {
                        final PeerEventListener l = downloadListener == null ? new MyDownloadListener() : downloadListener;
                        vPeerGroup.startBlockChainDownload(l);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        throw new RuntimeException(t);
                    }
                });
            }
        } catch (BlockStoreException e) {
            throw new IOException(e);
        } finally {
            if (walletStream != null) walletStream.close();
        }
    }

    private void installShutdownHook() {
        if (autoStop) Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override public void run() {
                try {
                    mLogger.info("MyWalletAppKit autoStop starting");
                    // Skip if shutDown has already been called.
                    if (vPeerGroup != null)
                        MyWalletAppKit.this.stopAndWait();
                    mLogger.info("MyWalletAppKit autoStop finished");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    @SuppressLint("NewApi")
	@Override
    protected void shutDown() throws Exception {
        mLogger.info("MyWalletAppKit shutDown starting");
        setAutoStop(false);	// Won't need this anymore.
        // Runs in a separate thread.
        try {
            vPeerGroup.stopAndWait();
            vWallet.saveToFile(vWalletFile);
            vStore.close();

            vPeerGroup = null;
            vWallet = null;
            vStore = null;
            vChain = null;
        } catch (BlockStoreException e) {
            throw new IOException(e);
        }
        mLogger.info("MyWalletAppKit shutDown finished");
    }

    public NetworkParameters params() {
        return params;
    }

    public BlockChain chain() {
        checkState(state() == State.STARTING || state() == State.RUNNING, "Cannot call until startup is complete");
        return vChain;
    }

    public SPVBlockStore store() {
        checkState(state() == State.STARTING || state() == State.RUNNING, "Cannot call until startup is complete");
        return vStore;
    }

    public Wallet wallet() {
        checkState(state() == State.STARTING || state() == State.RUNNING, "Cannot call until startup is complete");
        return vWallet;
    }

    public PeerGroup peerGroup() {
        checkState(state() == State.STARTING || state() == State.RUNNING, "Cannot call until startup is complete");
        return vPeerGroup;
    }

    public File directory() {
        return directory;
    }
}
