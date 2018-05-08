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
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.utils.ByteArray;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.db.AccountStore;
import org.tron.core.db.Manager;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.protos.Contract.TransferAssetContract;
import org.tron.protos.Protocol.Transaction.Result.code;

@Slf4j
public class TransferAssetActuator extends AbstractActuator {

  TransferAssetContract transferAssetContract;
  byte[] assetName;
  byte[] ownerAddress;
  byte[] toAddress;
  long amount;
  long fee;

  TransferAssetActuator(Any contract, Manager dbManager) {
    super(contract, dbManager);
    try {
      transferAssetContract = this.contract.unpack(TransferAssetContract.class);
      amount = transferAssetContract.getAmount();
      toAddress = transferAssetContract.getToAddress().toByteArray();
      ownerAddress = transferAssetContract.getOwnerAddress().toByteArray();
      assetName = transferAssetContract.getAssetName().toByteArray();
      fee = calcFee();
    } catch (InvalidProtocolBufferException e) {
      logger.error(e.getMessage(), e);
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
    }
  }

  @Override
  public boolean execute(TransactionResultCapsule ret) throws ContractExeException {
    try {
      AccountStore accountStore = this.dbManager.getAccountStore();
      AccountCapsule ownerAccountCapsule = accountStore.get(ownerAddress);
      if (!ownerAccountCapsule.reduceAssetAmount(ByteString.copyFrom(assetName), amount)) {
        throw new ContractExeException("reduceAssetAmount failed !");
      }
      accountStore.put(ownerAddress, ownerAccountCapsule);

      AccountCapsule toAccountCapsule = accountStore.get(toAddress);
      toAccountCapsule.addAssetAmount(ByteString.copyFrom(assetName), amount);
      accountStore.put(toAddress, toAccountCapsule);

      ret.setStatus(fee, code.SUCESS);
    } catch (Exception e) {
      ret.setStatus(fee, code.FAILED);
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
      if (transferAssetContract == null) {
        throw new ContractValidateException(
            "contract type error,expected type [TransferAssetContract],real type[" + contract
                .getClass() + "]");
      }

      if (!Wallet.addressValid(ownerAddress)) {
        throw new ContractValidateException("Invalidate ownerAddress");
      }
      if (!Wallet.addressValid(toAddress)) {
        throw new ContractValidateException("Invalidate toAddress");
      }
      Preconditions.checkNotNull(assetName, "AssetName is null");
      if (amount <= 0) {
        throw new ContractValidateException("Amount must greater than 0.");
      }

      if (Arrays.equals(ownerAddress, toAddress)) {
        throw new ContractValidateException("Cannot transfer asset to yourself.");
      }

      AccountStore accountStore = this.dbManager.getAccountStore();
      AccountCapsule ownerAccount = accountStore.get(ownerAddress);
      if (ownerAccount == null) {
        throw new ContractValidateException("No owner account!");
      }

      if (!this.dbManager.getAssetIssueStore().has(assetName)) {
        throw new ContractValidateException("No asset !");
      }

      Map<String, Long> asset = ownerAccount.getAssetMap();

      if (asset.isEmpty()) {
        throw new ContractValidateException("Owner no asset!");
      }

      Long assetBalance = asset.get(ByteArray.toStr(assetName));
      if (null == assetBalance || assetBalance <= 0) {
        throw new ContractValidateException("assetBalance must greater than 0.");
      }
      if (amount > assetBalance) {
        throw new ContractValidateException("assetBalance is not sufficient.");
      }

      // if account with to_address is not existed,  create it.
      AccountCapsule toAccount = accountStore.get(toAddress);
      if (toAccount == null) {
        throw new ContractValidateException("To account is not exit!");
      }

      assetBalance = toAccount.getAssetMap().get(ByteArray.toStr(assetName));
      if (assetBalance != null) {
        assetBalance = Math.addExact(assetBalance, amount); //check if overflow
      }
    } catch (Exception e) {
      throw new ContractValidateException(e.getMessage());
    }

    return true;
  }

  @Override
  public ByteString getOwnerAddress() {
    return null;
  }

  @Override
  public long calcFee() {
    return 0;
  }
}
