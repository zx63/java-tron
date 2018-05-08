package org.tron.core.actuator;

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
import org.tron.protos.Contract.FreezeBalanceContract;
import org.tron.protos.Protocol.Account.Frozen;
import org.tron.protos.Protocol.Transaction.Result.code;

@Slf4j
public class FreezeBalanceActuator extends AbstractActuator {

  FreezeBalanceContract freezeBalanceContract;
  byte[] ownerAddress;
  long frozenBalance;
  long frozenDuration;
  long fee;

  FreezeBalanceActuator(Any contract, Manager dbManager) {
    super(contract, dbManager);
    try {
      freezeBalanceContract = contract.unpack(FreezeBalanceContract.class);
      ownerAddress = freezeBalanceContract.getOwnerAddress().toByteArray();
      frozenBalance = freezeBalanceContract.getFrozenBalance();
      frozenDuration = freezeBalanceContract.getFrozenDuration();
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
      AccountStore accountStore = dbManager.getAccountStore();
      AccountCapsule accountCapsule = accountStore.get(ownerAddress);

      long now = System.currentTimeMillis();
      long duration = frozenDuration * 24 * 3600 * 1000L;

      long newBandwidth = accountCapsule.getBandwidth() + calculateBandwidth();

      long newBalance = accountCapsule.getBalance() - frozenBalance;

      long nowFrozenBalance = accountCapsule.getFrozenBalance();
      long newFrozenBalance = frozenBalance + nowFrozenBalance;

      Frozen newFrozen = Frozen.newBuilder()
          .setFrozenBalance(newFrozenBalance)
          .setExpireTime(now + duration)
          .build();

      long frozenCount = accountCapsule.getFrozenCount();
      assert (frozenCount >= 0);
      if (frozenCount == 0) {
        accountCapsule.setInstance(accountCapsule.getInstance().toBuilder()
            .addFrozen(newFrozen)
            .setBalance(newBalance)
            .setBandwidth(newBandwidth)
            .build());
      } else {
        assert frozenCount == 1;
        accountCapsule.setInstance(accountCapsule.getInstance().toBuilder()
            .setFrozen(0, newFrozen)
            .setBalance(newBalance)
            .setBandwidth(newBandwidth)
            .build()
        );
      }

      accountStore.put(ownerAddress, accountCapsule);

      ret.setStatus(fee, code.SUCESS);
    } catch (Exception e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(fee, code.FAILED);
      throw new ContractExeException(e.getMessage());
    }
    return true;
  }

  private long calculateBandwidth() {
    return frozenBalance * frozenDuration * dbManager.getDynamicPropertiesStore()
        .getBandwidthPerCoinday();
  }

  @Override
  public boolean validate() throws ContractValidateException {
    try {
      if (this.dbManager == null) {
        throw new ContractValidateException("No dbManager!");
      }
      if (freezeBalanceContract == null) {
        throw new ContractValidateException(
            "contract type error,expected type [FreezeBalanceContract],real type[" + contract
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

      if (frozenBalance < 1_000_000L) {
        throw new ContractValidateException("frozenBalance must be more than 1TRX");
      }

      if (frozenBalance > accountCapsule.getBalance()) {
        throw new ContractValidateException("frozenBalance must be less than accountBalance");
      }

//      long maxFrozenNumber = dbManager.getDynamicPropertiesStore().getMaxFrozenNumber();
//      if (accountCapsule.getFrozenCount() >= maxFrozenNumber) {
//        throw new ContractValidateException("max frozen number is: " + maxFrozenNumber);
//      }

      long minFrozenTime = dbManager.getDynamicPropertiesStore().getMinFrozenTime();
      long maxFrozenTime = dbManager.getDynamicPropertiesStore().getMaxFrozenTime();

      if (!(frozenDuration >= minFrozenTime && frozenDuration <= maxFrozenTime)) {
        throw new ContractValidateException(
            "frozenDuration must be less than " + maxFrozenTime + " days "
                + "and more than " + minFrozenTime + " days");
      }

    } catch (Exception ex) {
      ex.printStackTrace();
      throw new ContractValidateException(ex.getMessage());
    }

    return true;
  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return contract.unpack(FreezeBalanceContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return 0;
  }

}
