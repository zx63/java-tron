/*
 * java-tron is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * java-tron is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.tron.core.actuator;

import com.google.common.base.Preconditions;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.tron.common.utils.ByteArray;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.AssetIssueCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.db.AccountStore;
import org.tron.core.db.AssetIssueStore;
import org.tron.core.db.Manager;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.protos.Contract.ParticipateAssetIssueContract;
import org.tron.protos.Protocol.Transaction.Result.code;


@Slf4j
public class ParticipateAssetIssueActuator extends AbstractActuator {

  ParticipateAssetIssueContract participateAssetIssueContract;
  byte[] ownerAddress;
  byte[] toAddress;
  byte[] assetName;
  long amount;
  long fee;

  ParticipateAssetIssueActuator(Any contract, Manager dbManager) {
    super(contract, dbManager);
    try {
      participateAssetIssueContract = contract.unpack(ParticipateAssetIssueContract.class);
      ownerAddress = participateAssetIssueContract.getOwnerAddress().toByteArray();
      toAddress = participateAssetIssueContract.getToAddress().toByteArray();
      assetName = participateAssetIssueContract.getAssetName().toByteArray();
      amount = participateAssetIssueContract.getAmount();
      fee = calcFee();
    } catch (InvalidProtocolBufferException e) {
      logger.error(e.getMessage(), e);
    } catch (Exception e){
      logger.error(e.getMessage(), e);
    }
  }

  @Override
  public boolean execute(TransactionResultCapsule ret) throws ContractExeException {

    try {
      //subtract from owner address
      AccountStore accountStore = this.dbManager.getAccountStore();
      AccountCapsule ownerAccount = accountStore.get(ownerAddress);
      long balance = Math.subtractExact(ownerAccount.getBalance(), amount);
      balance = Math.subtractExact(balance, fee);
      ownerAccount.setBalance(balance);

      //calculate the exchange amount
      AssetIssueCapsule assetIssueCapsule = this.dbManager.getAssetIssueStore().get(assetName);
      long exchangeAmount = Math.multiplyExact(amount, assetIssueCapsule.getNum());
      exchangeAmount = Math.floorDiv(exchangeAmount, assetIssueCapsule.getTrxNum());
      ownerAccount.addAssetAmount(ByteString.copyFrom(assetName), exchangeAmount);

      //add to to_address
      AccountCapsule toAccount = accountStore.get(toAddress);
      toAccount.setBalance(Math.addExact(toAccount.getBalance(), amount));
      if (!toAccount.reduceAssetAmount(ByteString.copyFrom(assetName), exchangeAmount)) {
        throw new ContractExeException("reduceAssetAmount failed !");
      }

      //write to db
      accountStore.put(ownerAddress, ownerAccount);
      accountStore.put(toAddress, toAccount);

      ret.setStatus(fee, code.SUCESS);
    } catch (Exception e) {
      ret.setStatus(fee, code.FAILED);
      logger.debug(e.getMessage(), e);
      throw new ContractExeException(e.getMessage());
    }
    return true;
  }

  @Override
  public boolean validate() throws ContractValidateException {
    try {
      if (this.dbManager == null) {
        throw new ContractValidateException("No dbManager!");
      }
      if (participateAssetIssueContract == null) {
        throw new ContractValidateException(
            "contract type error,expected type [ParticipateAssetIssueContract],real type["
                + contract
                .getClass() + "]");
      }

      //Parameters check
      if (!Wallet.addressValid(ownerAddress)) {
        throw new ContractValidateException("Invalidate ownerAddress");
      }
      if (!Wallet.addressValid(toAddress)) {
        throw new ContractValidateException("Invalidate toAddress");
      }
      Preconditions.checkNotNull(assetName, "Asset name is null");

      if (amount <= 0) {
        throw new ContractValidateException("Amount must greater than 0!");
      }

      if (Arrays.equals(ownerAddress, toAddress)) {
        throw new ContractValidateException("Cannot participate asset Issue yourself !");
      }

      //Whether the account exist
      AccountStore accountStore = this.dbManager.getAccountStore();
      AccountCapsule accountCapsule = accountStore.get(ownerAddress);
      if (accountCapsule == null) {
        throw new ContractValidateException("Account does not exist!");
      }

      //Whether the balance is enough
      if (accountCapsule.getBalance() < Math.addExact(amount, fee)) {
        throw new ContractValidateException("No enough balance !");
      }

      //Whether have the mapping
      AssetIssueStore assetIssueStore = this.dbManager.getAssetIssueStore();
      AssetIssueCapsule assetIssueCapsule = assetIssueStore.get(assetName);
      if (assetIssueCapsule == null) {
        throw new ContractValidateException("No asset named " + ByteArray.toStr(assetName));
      }
      if (!Arrays.equals(toAddress, assetIssueCapsule.getOwnerAddress().toByteArray())) {
        throw new ContractValidateException(
            "The asset is not issued by " + ByteArray.toHexString(toAddress));
      }
      //Whether the exchange can be processed: to see if the exchange can be the exact int
      DateTime now = DateTime.now();
      if (now.getMillis() >= assetIssueCapsule.getEndTime() || now.getMillis() < assetIssueCapsule
          .getStartTime()) {
        throw new ContractValidateException("No longer valid period!");
      }
      int trxNum = assetIssueCapsule.getTrxNum();
      int num = assetIssueCapsule.getNum();
      long exchangeAmount = Math.multiplyExact(amount, num);
      exchangeAmount = Math.floorDiv(exchangeAmount, trxNum);
      if (exchangeAmount <= 0) {
        throw new ContractValidateException("Can not process the exchange!");
      }
      AccountCapsule toAccount = accountStore.get(toAddress);
      if (toAccount == null) {
        throw new ContractValidateException("To account does not exist!");
      }
      if (!toAccount.assetBalanceEnough(ByteString.copyFrom(assetName), exchangeAmount)) {
        throw new ContractValidateException("Asset balance is not enough !");
      }

    } catch (Exception e) {
      throw new ContractValidateException(e.getMessage());
    }

    return true;
  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return null;
  }

  @Override
  public long calcFee() {
    return 0;
  }
}
