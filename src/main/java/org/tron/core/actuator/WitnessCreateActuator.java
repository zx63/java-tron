package org.tron.core.actuator;

import com.google.common.base.Preconditions;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.Charset;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.utils.StringUtil;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.db.Manager;
import org.tron.core.exception.BalanceInsufficientException;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.protos.Contract.WitnessCreateContract;
import org.tron.protos.Protocol.Transaction.Result.code;

@Slf4j
public class WitnessCreateActuator extends AbstractActuator {

  WitnessCreateContract witnessCreateContract;
  byte[] ownerAddress;
  byte[] url;
  long fee;

  WitnessCreateActuator(final Any contract, final Manager dbManager) {
    super(contract, dbManager);
    try {
      witnessCreateContract = contract.unpack(WitnessCreateContract.class);
      ownerAddress = witnessCreateContract.getOwnerAddress().toByteArray();
      url = witnessCreateContract.getUrl().toByteArray();
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
      this.createWitness();
      ret.setStatus(fee, code.SUCESS);
    } catch (final Exception e) {
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
      if (witnessCreateContract == null) {
        throw new ContractValidateException(
            "contract type error,expected type [WitnessCreateContract],real type[" + contract
                .getClass() + "]");
      }
      String readableOwnerAddress = StringUtil.createReadableString(ownerAddress);

      if (!Wallet.addressValid(ownerAddress)) {
        throw new ContractValidateException("Invalidate address");
      }

      AccountCapsule accountCapsule = this.dbManager.getAccountStore().get(ownerAddress);
      if (accountCapsule == null) {
        throw new ContractValidateException("account[" + readableOwnerAddress + "] not exists");
      }
      long balance = accountCapsule.getBalance();

      Preconditions.checkArgument(
          !this.dbManager.getWitnessStore().has(ownerAddress),
          "Witness[" + readableOwnerAddress + "] has existed");

      Preconditions
          .checkArgument(balance >= dbManager.getDynamicPropertiesStore().getAccountUpgradeCost(),
              "balance < AccountUpgradeCost");

    } catch (final Exception ex) {
      ex.printStackTrace();
      throw new ContractValidateException(ex.getMessage());
    }
    return true;
  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return contract.unpack(WitnessCreateContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return 0;
  }

  private void createWitness() {
    //Create Witness by witnessCreateContract
    final WitnessCapsule witnessCapsule = new WitnessCapsule(ByteString.copyFrom(ownerAddress), 0,
        new String(url, Charset.forName("UTF-8")));

    logger.debug("createWitness,address[{}]", witnessCapsule.createReadableString());
    this.dbManager.getWitnessStore().put(ownerAddress, witnessCapsule);
    long cost = dbManager.getDynamicPropertiesStore().getAccountUpgradeCost();
    try {
      dbManager.adjustBalance(ownerAddress, -cost);
      dbManager.adjustBalance(this.dbManager.getAccountStore().getBlackhole().createDbKey(), cost);
    } catch (BalanceInsufficientException e) {
      throw new RuntimeException(e);
    }


  }

}
