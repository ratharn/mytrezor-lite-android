// Copyright (C) 2014  Bonsai Software, Inc.
// 
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
// 
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
// 
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package com.satoshilabs.btcreceive;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.crypto.DeterministicKey;
import com.google.bitcoin.crypto.HDKeyDerivation;
import com.google.bitcoin.crypto.KeyCrypter;

public class HDChain {

    private static Logger mLogger =
        LoggerFactory.getLogger(HDChain.class);

    private NetworkParameters	mParams;
    private DeterministicKey	mChainKey;
    private boolean				mIsReceive;
    private String				mChainName;

    private ArrayList<HDAddress>	mAddrs;

    static private final int	DESIRED_MARGIN = 32;
    static private final int	MAX_UNUSED_GAP = 8;

    public HDChain(NetworkParameters params,
                   DeterministicKey accountKey,
                   JSONObject chainNode)
        throws RuntimeException, JSONException {

        mParams = params;

        mChainName = chainNode.getString("name");
        mIsReceive = chainNode.getBoolean("isReceive");

        int chainnum = mIsReceive ? 0 : 1;

        mChainKey = HDKeyDerivation.deriveChildKey(accountKey, chainnum);

        mLogger.info("deserialized HDChain " + mChainName + ": " +
                     mChainKey.getPath());
        
        mAddrs = new ArrayList<HDAddress>();
        JSONArray addrobjs = chainNode.getJSONArray("addrs");
        for (int ii = 0; ii < addrobjs.length(); ++ii) {
            JSONObject addrNode = addrobjs.getJSONObject(ii);
            mAddrs.add(new HDAddress(mParams, mChainKey, addrNode));
        }
    }

    public JSONObject dumps() {
        try {
            JSONObject obj = new JSONObject();

            obj.put("name", mChainName);
            obj.put("isReceive", mIsReceive);

            JSONArray addrs = new JSONArray();
            for (HDAddress addr : mAddrs)
                addrs.put(addr.dumps());

            obj.put("addrs", addrs);

            return obj;
        }
        catch (JSONException ex) {
            throw new RuntimeException(ex);	// Shouldn't happen.
        }
    }

    public HDChain(NetworkParameters params,
                   DeterministicKey accountKey,
                   boolean isReceive,
                   String chainName) {

        mParams = params;
        mIsReceive = isReceive;
        int chainnum = mIsReceive ? 0 : 1;
        mChainKey = HDKeyDerivation.deriveChildKey(accountKey, chainnum);
        mChainName = chainName;

        int numAddrs = DESIRED_MARGIN;

        mLogger.info("created HDChain " + mChainName);
        
        mAddrs = new ArrayList<HDAddress>();
        for (int ii = 0; ii < numAddrs; ++ii)
            mAddrs.add(new HDAddress(mParams, mChainKey, ii));
    }

    public static int maxSafeExtend() {
        return DESIRED_MARGIN - MAX_UNUSED_GAP;
    }

    public boolean isReceive() {
        return mIsReceive;
    }

    public List<HDAddress> getAddresses() {
        return mAddrs;
    }

    public int numAddrs() {
        return mAddrs.size();
    }

    public void gatherAllKeys(KeyCrypter keyCrypter,
                              KeyParameter aesKey,
                              long creationTime,
                              List<ECKey> keys) {
        for (HDAddress hda : mAddrs)
            hda.gatherKey(keyCrypter, aesKey, creationTime, keys);
    }

    public void applyOutput(byte[] pubkey,
                            byte[] pubkeyhash,
                            long value,
                            boolean avail) {
        for (HDAddress hda : mAddrs)
            hda.applyOutput(pubkey, pubkeyhash, value, avail);
    }

    public void applyInput(byte[] pubkey, long value) {
        for (HDAddress hda : mAddrs)
            hda.applyInput(pubkey, value);
    }

    public void clearBalance() {
        for (HDAddress hda : mAddrs)
            hda.clearBalance();
    }

    public long balance() {
        long balance = 0;
        for (HDAddress hda : mAddrs)
            balance += hda.getBalance();
        return balance;
    }

    public long available() {
        long available = 0;
        for (HDAddress hda : mAddrs)
            available += hda.getAvailable();
        return available;
    }

    public void logBalance() {
        for (HDAddress hda: mAddrs)
            hda.logBalance();
    }

    public Address nextUnusedAddress() {
        for (HDAddress hda : mAddrs) {
            if (hda.isUnused())
                return hda.getAddress();
        }
        throw new RuntimeException("no unused address available");
    }

    public boolean hasPubKey(byte[] pubkey, byte[] pubkeyhash) {
        for (HDAddress hda : mAddrs) {
            if (hda.isMatch(pubkey, pubkeyhash))
                return true;
        }
        return false;
    }

    private int marginSize() {
        int count = 0;
        ListIterator li = mAddrs.listIterator(mAddrs.size());
        while (li.hasPrevious()) {
            HDAddress hda = (HDAddress) li.previous();
            if (!hda.isUnused())
                return count;
            ++count;
        }
        return count;
    }

    // Returns the number of addresses added.
    public int ensureMargins(Wallet wallet,
                             KeyCrypter keyCrypter,
                             KeyParameter aesKey) {
        // How many unused addresses do we have at the end of the chain?
        int numUnused = marginSize();

        // Do we have an ample margin?
        if (numUnused >= DESIRED_MARGIN) {
            return 0;
        }
        else {
            // How many addresses do we need to add?
            int numAdd = DESIRED_MARGIN - numUnused;

            mLogger.info(String.format("%s expanding margin, adding %d addrs",
                                       mChainKey.getPath(), numAdd));

            // Set the new keys creation time to now.
            long now = Utils.now().getTime() / 1000;

            // Add the addresses ...
            int newSize = mAddrs.size() + numAdd;
            ArrayList<ECKey> keys = new ArrayList<ECKey>();
            for (int ii = mAddrs.size(); ii < newSize; ++ii) {
                HDAddress hda = new HDAddress(mParams, mChainKey, ii);
                mAddrs.add(hda);
                hda.gatherKey(keyCrypter, aesKey, now, keys);
            }
            mLogger.info(String.format("adding %d keys", keys.size()));
            wallet.addKeys(keys);

            return numAdd;
        }
    }

    // Finds an address (if present) and returns a description
    // of it's wallet location.
    public HDAddressDescription findAddress(Address addr) {
        for (HDAddress hda : mAddrs) {
            if (hda.matchAddress(addr)) {
                // Caller will fill in the accountId.
                return new HDAddressDescription(this, hda);
            }
        }
        return null;
    }
}
