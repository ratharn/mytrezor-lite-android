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

import java.math.BigInteger;
import java.util.LinkedList;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.ScriptException;
import com.google.bitcoin.core.TransactionOutput;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.crypto.ChildNumber;
import com.google.bitcoin.crypto.DeterministicKey;
import com.google.bitcoin.crypto.HDKeyDerivation;
import com.google.bitcoin.crypto.KeyCrypter;
import com.google.bitcoin.script.Script;
import com.google.bitcoin.wallet.CoinSelection;
import com.google.bitcoin.wallet.CoinSelector;
import com.google.bitcoin.wallet.DefaultCoinSelector;

public class HDAccount {

    private static Logger mLogger =
        LoggerFactory.getLogger(HDAccount.class);

    private NetworkParameters	mParams;
    private DeterministicKey	mAccountKey;
    private String				mAccountName;

    private HDChain				mReceiveChain;
    private HDChain				mChangeChain;

    public HDAccount(NetworkParameters params,
                     DeterministicKey accountKey,
                     JSONObject acctNode) throws JSONException {

        mParams = params;
        mAccountKey = accountKey;

        mAccountName = acctNode.getString("name");

        mReceiveChain = new HDChain(mParams, mAccountKey,
                                    acctNode.getJSONObject("receive"));
        mChangeChain = new HDChain(mParams, mAccountKey,
                                   acctNode.getJSONObject("change"));

        mLogger.info("deserialized account " + mAccountName);
    }

    public JSONObject dumps() {
        try {
            JSONObject obj = new JSONObject();

            obj.put("name", mAccountName);
            obj.put("receive", mReceiveChain.dumps());
            obj.put("change", mChangeChain.dumps());

            return obj;
        }
        catch (JSONException ex) {
            throw new RuntimeException(ex);	// Shouldn't happen.
        }
    }

    public HDAccount(NetworkParameters params,
                     DeterministicKey accountKey,
                     String accountName) {

        mParams = params;
        mAccountKey = accountKey;
        mAccountName = accountName;

        mReceiveChain = new HDChain(mParams, mAccountKey, true, "Receive");
        mChangeChain = new HDChain(mParams, mAccountKey, false, "Change");

        mLogger.info("created account " + mAccountName);
    }

    public void gatherAllKeys(KeyCrypter keyCrypter,
                              KeyParameter aesKey,
                              long creationTime,
                              List<ECKey> keys) {
        mReceiveChain.gatherAllKeys(keyCrypter, aesKey, creationTime, keys);
        mChangeChain.gatherAllKeys(keyCrypter, aesKey, creationTime, keys);
    }

    public void applyOutput(byte[] pubkey,
                            byte[] pubkeyhash,
                            long value,
                            boolean avail) {
        mReceiveChain.applyOutput(pubkey, pubkeyhash, value, avail);
        mChangeChain.applyOutput(pubkey, pubkeyhash, value, avail);
    }

    public void applyInput(byte[] pubkey, long value) {
        mReceiveChain.applyInput(pubkey, value);
        mChangeChain.applyInput(pubkey, value);
    }

    public void clearBalance() {
        mReceiveChain.clearBalance();
        mChangeChain.clearBalance();
    }

    public boolean hasPubKey(byte[] pubkey, byte[] pubkeyhash) {
        if (mReceiveChain.hasPubKey(pubkey, pubkeyhash))
            return true;

        return mChangeChain.hasPubKey(pubkey, pubkeyhash);
    }

    public String xpubstr() {
        return mAccountKey.serializePubB58();
    }

    public String getName() {
        return mAccountName;
    }

    public void setName(String name) {
        mAccountName = name;
    }

    public HDChain getReceiveChain() {
        return mReceiveChain;
    }

    public HDChain getChangeChain() {
        return mChangeChain;
    }

    public long balance() {
        long balance = 0;
        balance += mReceiveChain.balance();
        balance += mChangeChain.balance();
        return balance;
    }

    public long available() {
        long available = 0;
        available += mReceiveChain.available();
        available += mChangeChain.available();
        return available;
    }

    public void logBalance() {
        mLogger.info(mAccountName + " balance " + Long.toString(balance()) +
                     ", " + "available " + Long.toString(available()));

        // Now log any active addresses in this account.
        mReceiveChain.logBalance();
        mChangeChain.logBalance();
    }

    public Address nextReceiveAddress() {
        return mReceiveChain.nextUnusedAddress();
    }

    public Address nextChangeAddress() {
        return mChangeChain.nextUnusedAddress();
    }

    public CoinSelector coinSelector() {
        return new AccountCoinSelector();
    }

    public class AccountCoinSelector implements CoinSelector {

        private DefaultCoinSelector mDefaultCoinSelector;

        public AccountCoinSelector() {
            mDefaultCoinSelector = new DefaultCoinSelector();
        }

        public CoinSelection select(BigInteger biTarget,
                                    LinkedList<TransactionOutput> candidates) {
            // Filter the candidates so only coins from this account
            // are considered.  Let the Wallet.DefaultCoinSelector do
            // all the remaining work.
            LinkedList<TransactionOutput> filtered =
                new LinkedList<TransactionOutput>();
            for (TransactionOutput to : candidates) {
				try {
                    byte[] pubkey = null;
                    byte[] pubkeyhash = null;
                    Script script = to.getScriptPubKey();
                    if (script.isSentToRawPubKey())
                        pubkey = script.getPubKey();
                    else
                        pubkeyhash = script.getPubKeyHash();

                    if (mReceiveChain.hasPubKey(pubkey, pubkeyhash))
                        filtered.add(to);
                    else if (mChangeChain.hasPubKey(pubkey, pubkeyhash))
                        filtered.add(to);
                    else
                        // Not in this account ...
                        continue;

				} catch (ScriptException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
            }

            // Does all the real work ...
            return mDefaultCoinSelector.select(biTarget, filtered);
        }
    }

    // Returns the largest number of addresses added to a chain.
    public int ensureMargins(Wallet wallet,
                              KeyCrypter keyCrypter,
                              KeyParameter aesKey) {
        int receiveAdded =
            mReceiveChain.ensureMargins(wallet, keyCrypter, aesKey);
        int changeAdded =
            mChangeChain.ensureMargins(wallet, keyCrypter, aesKey);

        return (receiveAdded > changeAdded) ? receiveAdded : changeAdded;
    }

    // Finds an address (if present) and returns a description
    // of it's wallet location.
    public HDAddressDescription findAddress(Address addr) {
        HDAddressDescription retval;

        // Try the receive chain first.
        retval = mReceiveChain.findAddress(addr);

        // If it wasn't there try the change chain.
        if (retval == null)
            retval = mChangeChain.findAddress(addr);

        // If we found this address update the hdAccount.
        if (retval != null)
            retval.setHDAccount(this);

        return retval;
    }
}
