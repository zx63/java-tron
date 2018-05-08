package org.tron.core.actuator;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.config.Parameter.ChainConstant;
import org.tron.core.db.AccountStore;
import org.tron.core.db.Manager;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.protos.Contract.TransferContract;
import org.tron.protos.Protocol.AccountType;
import org.tron.protos.Protocol.Transaction.Result.code;

@Slf4j
public class TransferActuator extends AbstractActuator {


  TransferActuator(Any contract, Manager dbManager) {
    super(contract, dbManager);
  }

  @Override
  public boolean execute(TransactionResultCapsule ret) throws ContractExeException {

    long fee = calcFee();
    try {
      TransferContract transferContract = contract.unpack(TransferContract.class);
      long amount = transferContract.getAmount();

      dbManager.adjustBalance(transferContract.getOwnerAddress().toByteArray(),
          -Math.addExact(amount, fee));
      dbManager.adjustBalance(transferContract.getToAddress().toByteArray(), amount);
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
    long fee = calcFee();
    try {
      if (!contract.is(TransferContract.class)) {
        throw new ContractValidateException(
            "contract type error,expected type [TransferContract],real type[" + contract
                .getClass() + "]");
      }
      TransferContract transferContract = this.contract.unpack(TransferContract.class);
      byte[] ownerAddress = transferContract.getOwnerAddress().toByteArray();
      byte[] toAddress = transferContract.getToAddress().toByteArray();
      if (!Wallet.addressValid(ownerAddress)) {
        throw new ContractValidateException("Invalidate ownerAddress");
      }
      if (!Wallet.addressValid(toAddress)) {
        throw new ContractValidateException("Invalidate toAddress");
      }

      if (Arrays.equals(ownerAddress, toAddress)) {
        throw new ContractValidateException("Cannot transfer trx to yourself.");
      }

      AccountStore accountStore = dbManager.getAccountStore();
      AccountCapsule ownerAccount = accountStore.get(ownerAddress);
      if (ownerAccount == null) {
        throw new ContractValidateException("Validate TransferContract error, no OwnerAccount.");
      }

      long balance = ownerAccount.getBalance();
      if (ownerAccount.getBalance() < fee) {
        throw new ContractValidateException("Validate TransferContract error, insufficient fee.");
      }
      long amount = transferContract.getAmount();
      if (amount <= 0) {
        throw new ContractValidateException("Amount must greater than 0.");
      }
      if (balance < Math.addExact(amount, fee)) {
        throw new ContractValidateException("balance is not sufficient.");
      }

      // if account with to_address is not existed,  create it.
      AccountCapsule toAccount = accountStore.get(toAddress);
      if (toAccount == null) {
        long min = dbManager.getDynamicPropertiesStore().getNonExistentAccountTransferMin();
        if (amount < min) {
          throw new ContractValidateException(
              "For a non-existent account transfer, the minimum amount is 1 TRX");
        }
        toAccount = new AccountCapsule(transferContract.getToAddress(), AccountType.Normal,
            System.currentTimeMillis());
        accountStore.put(toAddress, toAccount);
      } else {
        //check to account balance if overflow
        balance = Math
            .addExact(toAccount.getBalance(),
                amount);
      }
    } catch (Exception ex) {
      throw new ContractValidateException(ex.getMessage());
    }

    return true;
  }


  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return contract.unpack(TransferContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return ChainConstant.TRANSFER_FEE;
  }
}
