package org.tron.core.actuator;

import com.google.common.base.Preconditions;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.db.AccountStore;
import org.tron.core.db.Manager;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.protos.Contract.AccountUpdateContract;

@Slf4j
public class UpdateAccountActuator extends AbstractActuator {

  UpdateAccountActuator(Any contract, Manager dbManager) {
    super(contract, dbManager);
  }

  @Override
  public boolean execute(TransactionResultCapsule result) throws ContractExeException {
    long fee = calcFee();
    try {
      AccountUpdateContract accountUpdateContract = contract.unpack(AccountUpdateContract.class);
      byte[] ownerAddress = accountUpdateContract.getOwnerAddress().toByteArray();
      AccountStore accountStore = dbManager.getAccountStore();
      AccountCapsule account = accountStore.get(ownerAddress);

      account.setAccountName(accountUpdateContract.getAccountName().toByteArray());
      accountStore.put(ownerAddress, account);
      return true;
    } catch (Exception e) {
      logger.debug(e.getMessage(), e);
      throw new ContractExeException(e.getMessage());
    }
  }

  @Override
  public boolean validate() throws ContractValidateException {
    try {
      if (!contract.is(AccountUpdateContract.class)) {
        throw new ContractValidateException(
            "contract type error,expected type [AccountUpdateContract],real type[" + contract
                .getClass() + "]");
      }

      AccountUpdateContract accountUpdateContract = this.contract
          .unpack(AccountUpdateContract.class);
      //ToDo check accountName
      Preconditions.checkNotNull(accountUpdateContract.getAccountName(), "AccountName is null");
      byte[] ownerAddress = accountUpdateContract.getOwnerAddress().toByteArray();
      if (!Wallet.addressValid(ownerAddress)) {
        throw new ContractValidateException("Invalidate ownerAddress");
      }

      if (dbManager.getAccountStore().has(ownerAddress)) {
        throw new ContractValidateException("Account has existed");
      }
    } catch (Exception ex) {
      ex.printStackTrace();
      throw new ContractValidateException(ex.getMessage());
    }
    return true;
  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return contract.unpack(AccountUpdateContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return 0;
  }
}
