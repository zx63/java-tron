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
import lombok.extern.slf4j.Slf4j;
import org.tron.common.utils.ByteArray;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.AssetIssueCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.config.Parameter.ChainConstant;
import org.tron.core.db.AccountStore;
import org.tron.core.db.Manager;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.protos.Contract.AssetIssueContract;
import org.tron.protos.Protocol.Transaction.Result.code;

@Slf4j
public class AssetIssueActuator extends AbstractActuator {

  AssetIssueContract assetIssueContract;
  byte[] ownerAddress;
  byte[] assetName;
  long totalSupply;
  long trxNum;
  long num;
  long fee;

  AssetIssueActuator(Any contract, Manager dbManager) {
    super(contract, dbManager);
    try {
      assetIssueContract = contract.unpack(AssetIssueContract.class);
      ownerAddress = assetIssueContract.getOwnerAddress().toByteArray();
      assetName = assetIssueContract.getName().toByteArray();
      totalSupply = assetIssueContract.getTotalSupply();
      trxNum = assetIssueContract.getTrxNum();
      num = assetIssueContract.getNum();
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
      AssetIssueCapsule assetIssueCapsule = new AssetIssueCapsule(assetIssueContract);

      dbManager.getAssetIssueStore().put(assetName, assetIssueCapsule);
      dbManager.adjustBalance(ownerAddress, -fee);

      AccountStore accountStore = dbManager.getAccountStore();
      dbManager.adjustBalance(accountStore.getBlackhole().getAddress().toByteArray(),
          fee);//send to blackhole
      AccountCapsule accountCapsule = accountStore.get(ownerAddress);

      accountCapsule.addAsset(ByteArray.toStr(assetName), totalSupply);
      accountStore.put(ownerAddress, accountCapsule);
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
      if (assetIssueContract == null) {
        throw new ContractValidateException(
            "contract type error,expected type [AssetIssueContract],real type[" + contract
                .getClass() + "]");
      }

      if (!Wallet.addressValid(ownerAddress)) {
        throw new ContractValidateException("Invalidate ownerAddress");
      }
      Preconditions.checkNotNull(assetName, "name is null");

      if (totalSupply <= 0) {
        throw new ContractValidateException("TotalSupply must greater than 0!");
      }

      if (trxNum <= 0) {
        throw new ContractValidateException("TrxNum must greater than 0!");
      }

      if (num <= 0) {
        throw new ContractValidateException("Num must greater than 0!");
      }

      AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
      if (accountCapsule == null) {
        throw new ContractValidateException("Account not exists");
      }

      if (this.dbManager.getAssetIssueStore().get(assetName) != null) {
        throw new ContractValidateException("Token exists");
      }

      if (accountCapsule.getBalance() < fee) {
        throw new ContractValidateException("No enough blance for fee!");
      }

    } catch (Exception e) {
      throw new ContractValidateException(e.getMessage());
    }

    return false;
  }

  @Override
  public ByteString getOwnerAddress() {
    return null;
  }

  @Override
  public long calcFee() {
    return ChainConstant.ASSET_ISSUE_FEE;
  }


  public long calcUsage() {
    return 0;
  }
}
