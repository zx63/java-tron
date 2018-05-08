package org.tron.core.actuator;

import com.google.common.math.LongMath;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.utils.StringUtil;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.db.AccountStore;
import org.tron.core.db.Manager;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.protos.Contract.WithdrawBalanceContract;
import org.tron.protos.Protocol.Transaction.Result.code;

@Slf4j
public class WithdrawBalanceActuator extends AbstractActuator {

  WithdrawBalanceActuator(Any contract, Manager dbManager) {
    super(contract, dbManager);
  }


  @Override
  public boolean execute(TransactionResultCapsule ret) throws ContractExeException {
    long fee = calcFee();
    try {
      WithdrawBalanceContract withdrawBalanceContract = contract
          .unpack(WithdrawBalanceContract.class);
      AccountStore accountStore = dbManager.getAccountStore();
      byte[] ownerAddress = withdrawBalanceContract.getOwnerAddress().toByteArray();
      AccountCapsule accountCapsule = accountStore.get(ownerAddress);
      long oldBalance = accountCapsule.getBalance();
      long allowance = accountCapsule.getAllowance();

      long now = System.currentTimeMillis();
      accountCapsule.setInstance(accountCapsule.getInstance().toBuilder()
          .setBalance(oldBalance + allowance)
          .setAllowance(0L)
          .setLatestWithdrawTime(now)
          .build());
      accountStore.put(ownerAddress, accountCapsule);

      ret.setStatus(fee, code.SUCESS);
    } catch (Exception e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(fee, code.FAILED);
      throw new ContractExeException(e.getMessage());
    }
    return true;
  }

  @Override
  public boolean validate() throws ContractValidateException {
    try {
      if (!contract.is(WithdrawBalanceContract.class)) {
        throw new ContractValidateException(
            "contract type error,expected type [WithdrawBalanceContract],real type[" + contract
                .getClass() + "]");
      }

      WithdrawBalanceContract withdrawBalanceContract = this.contract
          .unpack(WithdrawBalanceContract.class);
      byte[] ownerAddress = withdrawBalanceContract.getOwnerAddress().toByteArray();
      if (!Wallet.addressValid(ownerAddress)) {
        throw new ContractValidateException("Invalidate address");
      }

      AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
      if (accountCapsule == null) {
        String readableOwnerAddress = StringUtil.createReadableString(ownerAddress);
        throw new ContractValidateException(
            "Account[" + readableOwnerAddress + "] not exists");
      }

      if (!dbManager.getWitnessStore().has(ownerAddress)) {
        String readableOwnerAddress = StringUtil.createReadableString(ownerAddress);
        throw new ContractValidateException(
            "Account[" + readableOwnerAddress + "] is not a witnessAccount");
      }

      long latestWithdrawTime = accountCapsule.getLatestWithdrawTime();
      long now = System.currentTimeMillis();
      long witnessAllowanceFrozenTime =
          dbManager.getDynamicPropertiesStore().getWitnessAllowanceFrozenTime() * 24 * 3600 * 1000L;

      if (now - latestWithdrawTime < witnessAllowanceFrozenTime) {
        throw new ContractValidateException("The last withdraw time is less than 24 hours");
      }

      if (accountCapsule.getAllowance() <= 0) {
        throw new ContractValidateException("witnessAccount does not have any allowance");
      }

      LongMath.checkedAdd(accountCapsule.getBalance(), accountCapsule.getAllowance());

    } catch (Exception ex) {
      ex.printStackTrace();
      throw new ContractValidateException(ex.getMessage());
    }

    return true;
  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return contract.unpack(WithdrawBalanceContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return 0;
  }

}
