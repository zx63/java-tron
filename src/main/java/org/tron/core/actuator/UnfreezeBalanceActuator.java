package org.tron.core.actuator;

import com.google.common.collect.Lists;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Iterator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.utils.StringUtil;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.db.AccountStore;
import org.tron.core.db.Manager;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.protos.Contract.UnfreezeBalanceContract;
import org.tron.protos.Protocol.Account.Frozen;
import org.tron.protos.Protocol.Transaction.Result.code;

@Slf4j
public class UnfreezeBalanceActuator extends AbstractActuator {

  UnfreezeBalanceContract unfreezeBalanceContract;
  byte[] ownerAddress;
  long fee;

  UnfreezeBalanceActuator(Any contract, Manager dbManager) {
    super(contract, dbManager);
    try {
      unfreezeBalanceContract = contract.unpack(UnfreezeBalanceContract.class);
      ownerAddress = unfreezeBalanceContract.getOwnerAddress().toByteArray();
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
      AccountStore accountStore = dbManager.getAccountStore();
      AccountCapsule accountCapsule = accountStore.get(ownerAddress);
      long oldBalance = accountCapsule.getBalance();
      long unfreezeBalance = 0L;
      List<Frozen> frozenList = Lists.newArrayList();
      frozenList.addAll(accountCapsule.getFrozenList());
      Iterator<Frozen> iterator = frozenList.iterator();
      long now = System.currentTimeMillis();
      while (iterator.hasNext()) {
        Frozen next = iterator.next();
        if (next.getExpireTime() <= now) {
          unfreezeBalance += next.getFrozenBalance();
          iterator.remove();
        }
      }

      accountCapsule.setInstance(accountCapsule.getInstance().toBuilder()
          .setBalance(oldBalance + unfreezeBalance)
          .clearFrozen().addAllFrozen(frozenList).build());
      accountStore.put(accountCapsule.createDbKey(), accountCapsule);

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
      if (this.dbManager == null) {
        throw new ContractValidateException("No dbManager!");
      }
      if (unfreezeBalanceContract == null){
        throw new ContractValidateException(
            "contract type error,expected type [UnfreezeBalanceContract],real type[" + contract
                .getClass() + "]");
      }

      if (!Wallet.addressValid(ownerAddress)) {
        throw new ContractValidateException("Invalidate address");
      }

      AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
      if (accountCapsule == null) {
        String readableOwnerAddress = StringUtil.createReadableString(ownerAddress);
        throw new ContractValidateException(
            "Account[" + readableOwnerAddress + "] not exists");
      }

      if (accountCapsule.getFrozenCount() <= 0) {
        throw new ContractValidateException("no frozenBalance");
      }

      long now = System.currentTimeMillis();
      long allowedUnfreezeCount = accountCapsule.getFrozenList().stream()
          .filter(frozen -> frozen.getExpireTime() <= now).count();
      if (allowedUnfreezeCount <= 0) {
        throw new ContractValidateException("It's not time to unfreeze.");
      }

    } catch (Exception ex) {
      ex.printStackTrace();
      throw new ContractValidateException(ex.getMessage());
    }

    return true;
  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return contract.unpack(UnfreezeBalanceContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return 0;
  }

}
