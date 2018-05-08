package org.tron.core.actuator;

import com.google.common.math.LongMath;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Iterator;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.StringUtil;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.db.AccountStore;
import org.tron.core.db.Manager;
import org.tron.core.db.WitnessStore;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.protos.Contract.VoteWitnessContract;
import org.tron.protos.Contract.VoteWitnessContract.Vote;
import org.tron.protos.Protocol.Transaction.Result.code;

@Slf4j
public class VoteWitnessActuator extends AbstractActuator {

  VoteWitnessContract voteContract;
  byte[] ownerAddress;
  long fee;

  VoteWitnessActuator(Any contract, Manager dbManager) {
    super(contract, dbManager);
    try {
      voteContract = contract.unpack(VoteWitnessContract.class);
      ownerAddress = voteContract.getOwnerAddress().toByteArray();
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
      countVoteAccount(voteContract);
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
      if (voteContract == null) {
        throw new ContractValidateException(
            "contract type error,expected type [VoteWitnessContract],real type[" + contract
                .getClass() + "]");
      }

      if (!Wallet.addressValid(ownerAddress)) {
        throw new ContractValidateException("Invalidate address");
      }
      String readableOwnerAddress = StringUtil.createReadableString(ownerAddress);

      AccountStore accountStore = dbManager.getAccountStore();
      Iterator<Vote> iterator = voteContract.getVotesList().iterator();
      WitnessStore witnessStore = dbManager.getWitnessStore();
      while (iterator.hasNext()) {
        Vote vote = iterator.next();
        byte[] bytes = vote.getVoteAddress().toByteArray();
        String readableWitnessAddress = StringUtil.createReadableString(vote.getVoteAddress());
        if (!accountStore.has(bytes)) {
          throw new ContractValidateException(
              "Account[" + readableWitnessAddress + "] not exists");
        }
        if (!witnessStore.has(bytes)) {
          throw new ContractValidateException(
              "Witness[" + readableWitnessAddress + "] not exists");
        }
      }

      AccountCapsule ownerAccount = accountStore.get(ownerAddress);
      if (ownerAccount == null) {
        throw new ContractValidateException(
            "Account[" + readableOwnerAddress + "] not exists");
      }

      if (voteContract.getVotesCount() > dbManager.getDynamicPropertiesStore().getMaxVoteNumber()) {
        throw new ContractValidateException(
            "VoteNumber more than maxVoteNumber[30]");
      }

      long share = ownerAccount.getShare();

      Long sum = 0L;
      for (Vote vote : voteContract.getVotesList()) {
        sum = LongMath.checkedAdd(sum, vote.getVoteCount());
      }

      sum = LongMath.checkedMultiply(sum, 1000000L); //trx -> drop. The vote count is based on TRX
      if (sum > share) {
        throw new ContractValidateException(
            "The total number of votes[" + sum + "] is greater than the share[" + share + "]");
      }

    } catch (Exception ex) {
      ex.printStackTrace();
      throw new ContractValidateException(ex.getMessage());
    }

    return true;
  }

  private void countVoteAccount(VoteWitnessContract voteContract) {

    AccountCapsule accountCapsule = dbManager.getAccountStore()
        .get(voteContract.getOwnerAddress().toByteArray());

    accountCapsule.setInstance(accountCapsule.getInstance().toBuilder().clearVotes().build());

    voteContract.getVotesList().forEach(vote -> {
      //  String toStringUtf8 = vote.getVoteAddress().toStringUtf8();

      logger.debug("countVoteAccount,address[{}]",
          ByteArray.toHexString(vote.getVoteAddress().toByteArray()));

      accountCapsule.addVotes(vote.getVoteAddress(),
          vote.getVoteCount());
    });

    dbManager.getAccountStore().put(accountCapsule.createDbKey(), accountCapsule);
  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return contract.unpack(VoteWitnessContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return 0;
  }

}
