package org.tron.core.actuator;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.Charset;
import lombok.extern.slf4j.Slf4j;
import org.tron.core.Wallet;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.db.Manager;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.protos.Contract.WitnessUpdateContract;
import org.tron.protos.Protocol.Transaction.Result.code;

@Slf4j
public class WitnessUpdateActuator extends AbstractActuator {

  WitnessUpdateContract witnessUpdateContract;
  byte[] ownerAddress;
  byte[] url;
  long fee;

  WitnessUpdateActuator(final Any contract, final Manager dbManager) {
    super(contract, dbManager);
    try {
      witnessUpdateContract = this.contract.unpack(WitnessUpdateContract.class);
      ownerAddress = witnessUpdateContract.getOwnerAddress().toByteArray();
      url = witnessUpdateContract.getUpdateUrl().toByteArray();
      fee = calcFee();
    } catch (InvalidProtocolBufferException e) {
      logger.error(e.getMessage(), e);
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
    }
  }

  private void updateWitness() {
    WitnessCapsule witnessCapsule = this.dbManager.getWitnessStore().get(ownerAddress);
    witnessCapsule.setUrl(new String(url, Charset.forName("UTF-8")));
    this.dbManager.getWitnessStore().put(ownerAddress, witnessCapsule);
  }

  @Override
  public boolean execute(TransactionResultCapsule ret) throws ContractExeException {
    try {
      this.updateWitness();
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
      if (witnessUpdateContract == null) {
        throw new ContractValidateException(
            "contract type error,expected type [WitnessUpdateContract],real type[" + this.contract
                .getClass() + "]");
      }

      if (!Wallet.addressValid(ownerAddress)) {
        throw new ContractValidateException("Invalidate address");
      }
      if (this.dbManager.getWitnessStore().get(ownerAddress) == null) {
        throw new ContractValidateException("Witness not existed");
      }
    } catch (final Exception ex) {
      ex.printStackTrace();
      throw new ContractValidateException(ex.getMessage());
    }

    return true;
  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return contract.unpack(WitnessUpdateContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return 0;
  }
}
