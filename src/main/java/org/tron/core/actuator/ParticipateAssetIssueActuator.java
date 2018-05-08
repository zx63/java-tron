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
import org.tron.protos.Contract;
import org.tron.protos.Protocol.Transaction.Result.code;


@Slf4j
public class ParticipateAssetIssueActuator extends AbstractActuator {

  ParticipateAssetIssueActuator(Any contract, Manager dbManager) {
    super(contract, dbManager);
  }

  @Override
  public boolean execute(TransactionResultCapsule ret) throws ContractExeException {
    long fee = calcFee();

    try {
      Contract.ParticipateAssetIssueContract participateAssetIssueContract =
          contract.unpack(Contract.ParticipateAssetIssueContract.class);

      long cost = participateAssetIssueContract.getAmount();

      //subtract from owner address
      byte[] ownerAddressBytes = participateAssetIssueContract.getOwnerAddress().toByteArray();
      ByteString assetName = participateAssetIssueContract.getAssetName();
      AccountStore accountStore = this.dbManager.getAccountStore();
      AccountCapsule ownerAccount = accountStore.get(ownerAddressBytes);
      long balance = Math.subtractExact(ownerAccount.getBalance(), cost);
      balance = Math.subtractExact(balance, fee);
      ownerAccount.setBalance(balance);

      //calculate the exchange amount
      AssetIssueCapsule assetIssueCapsule =
          this.dbManager.getAssetIssueStore()
              .get(assetName.toByteArray());
      long exchangeAmount = Math.multiplyExact(cost, assetIssueCapsule.getNum());
      exchangeAmount = Math.floorDiv(exchangeAmount, assetIssueCapsule.getTrxNum());
      ownerAccount.addAssetAmount(assetName, exchangeAmount);

      //add to to_address
      byte[] toAddressBytes = participateAssetIssueContract.getToAddress().toByteArray();
      AccountCapsule toAccount = accountStore.get(toAddressBytes);
      toAccount.setBalance(Math.addExact(toAccount.getBalance(), cost));
      if (!toAccount.reduceAssetAmount(assetName, exchangeAmount)) {
        throw new ContractExeException("reduceAssetAmount failed !");
      }

      //write to db
      accountStore.put(ownerAddressBytes, ownerAccount);
      accountStore.put(toAddressBytes, toAccount);

      ret.setStatus(fee, code.SUCESS);

      return true;
    } catch (Exception e) {
      ret.setStatus(fee, code.FAILED);
      logger.debug(e.getMessage(), e);
      throw new ContractExeException(e.getMessage());
    }
  }

  @Override
  public boolean validate() throws ContractValidateException {
    if (!this.contract.is(Contract.ParticipateAssetIssueContract.class)) {
      throw new ContractValidateException();
    }

    try {
      final Contract.ParticipateAssetIssueContract participateAssetIssueContract =
          this.contract.unpack(Contract.ParticipateAssetIssueContract.class);

      byte[] addressBytes = participateAssetIssueContract.getOwnerAddress().toByteArray();
      byte[] toAddress = participateAssetIssueContract.getToAddress().toByteArray();
      ByteString assetName = participateAssetIssueContract.getAssetName();
      long amount = participateAssetIssueContract.getAmount();
      //Parameters check
      if (!Wallet.addressValid(addressBytes)) {
        throw new ContractValidateException("Invalidate ownerAddress");
      }
      if (!Wallet.addressValid(toAddress)) {
        throw new ContractValidateException("Invalidate toAddress");
      }
      Preconditions.checkNotNull(assetName, "Asset name is null");
      byte[] assetNameBytes = assetName.toByteArray();

      if (amount <= 0) {
        throw new ContractValidateException("Amount must greater than 0!");
      }

      if (Arrays.equals(addressBytes, toAddress)) {
        throw new ContractValidateException("Cannot participate asset Issue yourself !");
      }

      //Whether the account exist
      AccountStore accountStore = this.dbManager.getAccountStore();
      AccountCapsule accountCapsule = accountStore.get(addressBytes);
      if (accountCapsule == null) {
        throw new ContractValidateException("Account does not exist!");
      }

      long fee = calcFee();
      //Whether the balance is enough
      if (accountCapsule.getBalance() < Math.addExact(amount, fee)) {
        throw new ContractValidateException("No enough balance !");
      }

      //Whether have the mapping
      AssetIssueStore assetIssueStore = this.dbManager.getAssetIssueStore();
      AssetIssueCapsule assetIssueCapsule = assetIssueStore.get(assetNameBytes);
      if (assetIssueCapsule == null) {
        throw new ContractValidateException("No asset named " + ByteArray.toStr(assetNameBytes));
      }
      if (!participateAssetIssueContract.getToAddress()
          .equals(assetIssueCapsule.getOwnerAddress())) {
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
      if (!toAccount.assetBalanceEnough(assetName, exchangeAmount)) {
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
