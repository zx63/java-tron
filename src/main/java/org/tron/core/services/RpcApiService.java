package org.tron.core.services;

import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.tron.api.DatabaseGrpc.DatabaseImplBase;
import org.tron.api.GrpcAPI;
import org.tron.api.GrpcAPI.AccountNetMessage;
import org.tron.api.GrpcAPI.AccountPaginated;
import org.tron.api.GrpcAPI.Address;
import org.tron.api.GrpcAPI.AssetIssueList;
import org.tron.api.GrpcAPI.BlockLimit;
import org.tron.api.GrpcAPI.BlockList;
import org.tron.api.GrpcAPI.BlockReference;
import org.tron.api.GrpcAPI.BytesMessage;
import org.tron.api.GrpcAPI.EmptyMessage;
import org.tron.api.GrpcAPI.Node;
import org.tron.api.GrpcAPI.NodeList;
import org.tron.api.GrpcAPI.NumberMessage;
import org.tron.api.GrpcAPI.TransactionList;
import org.tron.api.GrpcAPI.WitnessList;
import org.tron.api.WalletExtensionGrpc;
import org.tron.api.WalletGrpc.WalletImplBase;
import org.tron.api.WalletSolidityGrpc.WalletSolidityImplBase;
import org.tron.common.application.Service;
import org.tron.common.overlay.discover.node.NodeHandler;
import org.tron.common.overlay.discover.node.NodeManager;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.StringUtil;
import org.tron.core.Constant;
import org.tron.core.Wallet;
import org.tron.core.WalletSolidity;
import org.tron.core.actuator.Actuator;
import org.tron.core.actuator.ActuatorFactory;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.db.BandwidthProcessor;
import org.tron.core.db.Manager;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.HeaderNotFound;
import org.tron.core.exception.StoreException;
import org.tron.protos.Contract;
import org.tron.protos.Contract.AccountCreateContract;
import org.tron.protos.Contract.AssetIssueContract;
import org.tron.protos.Contract.ParticipateAssetIssueContract;
import org.tron.protos.Contract.TransferAssetContract;
import org.tron.protos.Contract.TransferContract;
import org.tron.protos.Contract.UnfreezeAssetContract;
import org.tron.protos.Contract.VoteWitnessContract;
import org.tron.protos.Contract.WitnessCreateContract;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.Block;
import org.tron.protos.Protocol.DynamicProperties;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;

@Component
@Slf4j
public class RpcApiService implements Service {

  private int port = Args.getInstance().getRpcPort();
  private Server apiServer;

  @Autowired
  private Manager dbManager;
  @Autowired
  private NodeManager nodeManager;
  @Autowired
  private WalletSolidity walletSolidity;
  @Autowired
  private Wallet wallet;

  private static final long BLOCK_LIMIT_NUM = 100;
  private static final long TRANSACTION_LIMIT_NUM = 1000;

  @Override
  public void init() {
  }

  @Override
  public void init(Args args) {
  }

  @Override
  public void start() {
    try {
      NettyServerBuilder serverBuilder = NettyServerBuilder.forPort(port)
          .addService(new DatabaseApi());

      Args args = Args.getInstance();

      if (args.getRpcThreadNum() > 0) {
        serverBuilder = serverBuilder
            .executor(Executors.newFixedThreadPool(args.getRpcThreadNum()));
      }

      if (args.isSolidityNode()) {
        serverBuilder = serverBuilder.addService(new WalletSolidityApi());
        if (args.isWalletExtensionApi()) {
          serverBuilder = serverBuilder.addService(new WalletExtensionApi());
        }
      } else {
        serverBuilder = serverBuilder.addService(new WalletApi());
        serverBuilder = serverBuilder.addService(new WalletSolidityApi());
        serverBuilder = serverBuilder.addService(new WalletExtensionApi());
      }

      // Set configs from config.conf or default value
      serverBuilder
          .maxConcurrentCallsPerConnection(args.getMaxConcurrentCallsPerConnection())
          .flowControlWindow(args.getFlowControlWindow())
          .maxConnectionIdle(args.getMaxConnectionIdleInMillis(), TimeUnit.MILLISECONDS)
          .maxConnectionAge(args.getMaxConnectionAgeInMillis(), TimeUnit.MILLISECONDS)
          .maxMessageSize(args.getMaxMessageSize())
          .maxHeaderListSize(args.getMaxHeaderListSize());

      apiServer = serverBuilder.build().start();
    } catch (IOException e) {
      logger.debug(e.getMessage(), e);
    }

    logger.info("RpcApiService started, listening on " + port);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.err.println("*** shutting down gRPC server since JVM is shutting down");
      //server.this.stop();
      System.err.println("*** server shut down");
    }));
  }


  /**
   * DatabaseApi.
   */
  private class DatabaseApi extends DatabaseImplBase {

    @Override
    public void getBlockReference(org.tron.api.GrpcAPI.EmptyMessage request,
        io.grpc.stub.StreamObserver<org.tron.api.GrpcAPI.BlockReference> responseObserver) {
      long headBlockNum = dbManager.getDynamicPropertiesStore()
          .getLatestBlockHeaderNumber();
      byte[] blockHeaderHash = dbManager.getDynamicPropertiesStore()
          .getLatestBlockHeaderHash().getBytes();
      BlockReference ref = BlockReference.newBuilder()
          .setBlockHash(ByteString.copyFrom(blockHeaderHash))
          .setBlockNum(headBlockNum)
          .build();
      responseObserver.onNext(ref);
      responseObserver.onCompleted();
    }

    @Override
    public void getNowBlock(EmptyMessage request, StreamObserver<Block> responseObserver) {
      Block block = null;
      try {
        block = dbManager.getHead().getInstance();
      } catch (StoreException e) {
        logger.error(e.getMessage());
      }
      responseObserver.onNext(block);
      responseObserver.onCompleted();
    }

    @Override
    public void getBlockByNum(NumberMessage request, StreamObserver<Block> responseObserver) {
      Block block = null;
      try {
        block = dbManager.getBlockByNum(request.getNum()).getInstance();
      } catch (StoreException e) {
        logger.error(e.getMessage());
      }
      responseObserver.onNext(block);
      responseObserver.onCompleted();
    }

    @Override
    public void getDynamicProperties(EmptyMessage request,
        StreamObserver<DynamicProperties> responseObserver) {
      DynamicProperties.Builder builder = DynamicProperties.newBuilder();
      builder.setLastSolidityBlockNum(
          dbManager.getDynamicPropertiesStore().getLatestSolidifiedBlockNum());
      DynamicProperties dynamicProperties = builder.build();
      responseObserver.onNext(dynamicProperties);
      responseObserver.onCompleted();
    }
  }

  /**
   * WalletSolidityApi.
   */
  private class WalletSolidityApi extends WalletSolidityImplBase {

    @Override
    public void getAccount(Account request, StreamObserver<Account> responseObserver) {
      ByteString addressBs = request.getAddress();
      if (addressBs != null) {
        Account reply = wallet.getAccount(request);
        if (reply == null) {
          responseObserver.onNext(null);
        } else {
          AccountCapsule accountCapsule = new AccountCapsule(reply);
          BandwidthProcessor processor = new BandwidthProcessor(dbManager);
          processor.updateUsage(accountCapsule);
          responseObserver.onNext(accountCapsule.getInstance());
        }
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
    }

    @Override
    public void listWitnesses(EmptyMessage request, StreamObserver<WitnessList> responseObserver) {
      responseObserver.onNext(wallet.getWitnessList());
      responseObserver.onCompleted();
    }

    @Override
    public void getAssetIssueList(EmptyMessage request,
        StreamObserver<AssetIssueList> responseObserver) {
      responseObserver.onNext(wallet.getAssetIssueList());
      responseObserver.onCompleted();
    }

    @Override
    public void getNowBlock(EmptyMessage request, StreamObserver<Block> responseObserver) {
      responseObserver.onNext(wallet.getNowBlock());
      responseObserver.onCompleted();
    }

    @Override
    public void getBlockByNum(NumberMessage request, StreamObserver<Block> responseObserver) {
      long num = request.getNum();
      if (num >= 0) {
        Block reply = wallet.getBlockByNum(num);
        responseObserver.onNext(reply);
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
    }
  }

  /**
   * WalletExtensionApi.
   */
  private class WalletExtensionApi extends WalletExtensionGrpc.WalletExtensionImplBase {

    @Override
    public void getTransactionsFromThis(AccountPaginated request,
        StreamObserver<GrpcAPI.TransactionList> responseObserver) {
      ByteString thisAddress = request.getAccount().getAddress();
      long offset = request.getOffset();
      long limit = request.getLimit();
      if (null != thisAddress && offset >= 0 && limit >= 0) {
        TransactionList reply = walletSolidity
            .getTransactionsFromThis(thisAddress, offset, limit);
        responseObserver.onNext(reply);
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
    }

    @Override
    public void getTransactionsToThis(AccountPaginated request,
        StreamObserver<GrpcAPI.TransactionList> responseObserver) {
      ByteString toAddress = request.getAccount().getAddress();
      long offset = request.getOffset();
      long limit = request.getLimit();
      if (null != toAddress && offset >= 0 && limit >= 0) {
        TransactionList reply = walletSolidity
            .getTransactionsToThis(toAddress, offset, limit);
        responseObserver.onNext(reply);
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
    }

  }

  /**
   * WalletApi.
   */
  private class WalletApi extends WalletImplBase {

    @Override
    public void getAccount(Account req, StreamObserver<Account> responseObserver) {
      ByteString addressBs = req.getAddress();
      if (addressBs != null) {
        Account reply = wallet.getAccount(req);
        responseObserver.onNext(reply);
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
    }

    @Override
    public void createTransaction(TransferContract request,
        StreamObserver<Transaction> responseObserver) {
      try {
        responseObserver
            .onNext(
                createTransactionCapsule(request, ContractType.TransferContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug("ContractValidateException: {}", e.getMessage());
      }
      responseObserver.onCompleted();
    }

    private TransactionCapsule createTransactionCapsule(com.google.protobuf.Message message,
        ContractType contractType) throws ContractValidateException {
      TransactionCapsule trx = new TransactionCapsule(message, contractType);
      List<Actuator> actList = ActuatorFactory.createActuator(trx, dbManager);
      for (Actuator act : actList) {
        act.validate();
      }
      try {
        BlockCapsule headBlock = null;
        List<BlockCapsule> blockList = dbManager.getBlockStore().getBlockByLatestNum(1);
        if (CollectionUtils.isEmpty(blockList)) {
          throw new HeaderNotFound("latest block not found");
        } else {
          headBlock = blockList.get(0);
        }
        trx.setReference(headBlock.getNum(), headBlock.getBlockId().getBytes());
        long expiration = headBlock.getTimeStamp() + Constant.TRANSACTION_DEFAULT_EXPIRATION_TIME;
        trx.setExpiration(expiration);
      } catch (HeaderNotFound headerNotFound) {
        headerNotFound.printStackTrace();
      }
      return trx;
    }

    @Override
    public void broadcastTransaction(Transaction req,
        StreamObserver<GrpcAPI.Return> responseObserver) {
      GrpcAPI.Return retur = wallet.broadcastTransaction(req);
      responseObserver.onNext(retur);
      responseObserver.onCompleted();
    }

    @Override
    public void createAssetIssue(AssetIssueContract request,
        StreamObserver<Transaction> responseObserver) {
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.AssetIssueContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver.onNext(null);
        logger.debug("ContractValidateException: {}", e.getMessage());
      }
      responseObserver.onCompleted();
    }

    @Override
    public void unfreezeAsset(UnfreezeAssetContract request,
        StreamObserver<Transaction> responseObserver) {
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.UnfreezeAssetContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver.onNext(null);
        logger.debug("ContractValidateException: {}", e.getMessage());
      }
      responseObserver.onCompleted();
    }

    //refactor、test later
    private void checkVoteWitnessAccount(VoteWitnessContract req) {
      //send back to cli
      ByteString ownerAddress = req.getOwnerAddress();
      Preconditions.checkNotNull(ownerAddress, "OwnerAddress is null");

      AccountCapsule account = dbManager.getAccountStore().get(ownerAddress.toByteArray());
      Preconditions.checkNotNull(account,
          "OwnerAddress[" + StringUtil.createReadableString(ownerAddress) + "] not exists");

      int votesCount = req.getVotesCount();
      Preconditions.checkArgument(votesCount <= 0, "VotesCount[" + votesCount + "] <= 0");
      Preconditions.checkArgument(account.getTronPower() < votesCount,
          "tron power[" + account.getTronPower() + "] <  VotesCount[" + votesCount + "]");

      req.getVotesList().forEach(vote -> {
        ByteString voteAddress = vote.getVoteAddress();
        WitnessCapsule witness = dbManager.getWitnessStore()
            .get(voteAddress.toByteArray());
        String readableWitnessAddress = StringUtil.createReadableString(voteAddress);

        Preconditions.checkNotNull(witness, "witness[" + readableWitnessAddress + "] not exists");
        Preconditions.checkArgument(vote.getVoteCount() <= 0,
            "VoteAddress[" + readableWitnessAddress + "],VotesCount[" + vote
                .getVoteCount() + "] <= 0");
      });
    }

    @Override
    public void voteWitnessAccount(VoteWitnessContract request,
        StreamObserver<Transaction> responseObserver) {
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.VoteWitnessContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug("ContractValidateException: {}", e.getMessage());
      }
      responseObserver.onCompleted();
    }

    @Override
    public void createWitness(WitnessCreateContract request,
        StreamObserver<Transaction> responseObserver) {
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.WitnessCreateContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug("ContractValidateException: {}", e.getMessage());
      }
      responseObserver.onCompleted();
    }

    @Override
    public void createAccount(AccountCreateContract request,
        StreamObserver<Transaction> responseObserver) {
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.AccountCreateContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug("ContractValidateException: {}", e.getMessage());
      }
      responseObserver.onCompleted();
    }


    @Override
    public void updateWitness(Contract.WitnessUpdateContract request,
        StreamObserver<Transaction> responseObserver) {
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.WitnessUpdateContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug("ContractValidateException: {}", e.getMessage());
      }
      responseObserver.onCompleted();
    }

    @Override
    public void updateAccount(Contract.AccountUpdateContract request,
        StreamObserver<Transaction> responseObserver) {
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.AccountUpdateContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug("ContractValidateException: {}", e.getMessage());
      }
      responseObserver.onCompleted();
    }

    @Override
    public void updateAsset(Contract.UpdateAssetContract request,
        StreamObserver<Transaction> responseObserver) {
      try {
        responseObserver.onNext(
            createTransactionCapsule(request,
                ContractType.UpdateAssetContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug("ContractValidateException", e.getMessage());
      }
      responseObserver.onCompleted();
    }

    @Override
    public void freezeBalance(Contract.FreezeBalanceContract request,
        StreamObserver<Transaction> responseObserver) {
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.FreezeBalanceContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug("ContractValidateException: {}", e.getMessage());
      }
      responseObserver.onCompleted();
    }

    @Override
    public void unfreezeBalance(Contract.UnfreezeBalanceContract request,
        StreamObserver<Transaction> responseObserver) {
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.UnfreezeBalanceContract)
                .getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug("ContractValidateException: {}", e.getMessage());
      }
      responseObserver.onCompleted();
    }

    @Override
    public void withdrawBalance(Contract.WithdrawBalanceContract request,
        StreamObserver<Transaction> responseObserver) {
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.WithdrawBalanceContract)
                .getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug("ContractValidateException: {}", e.getMessage());
      }
      responseObserver.onCompleted();
    }

    @Override
    public void getNowBlock(EmptyMessage request, StreamObserver<Block> responseObserver) {
      responseObserver.onNext(wallet.getNowBlock());
      responseObserver.onCompleted();
    }

    @Override
    public void getBlockByNum(NumberMessage request, StreamObserver<Block> responseObserver) {
      responseObserver.onNext(wallet.getBlockByNum(request.getNum()));
      responseObserver.onCompleted();
    }

    @Override
    public void listNodes(EmptyMessage request, StreamObserver<NodeList> responseObserver) {
      List<NodeHandler> handlerList = nodeManager.dumpActiveNodes();

      Map<String, NodeHandler> nodeHandlerMap = new HashMap<>();
      for (NodeHandler handler : handlerList) {
        String key = handler.getNode().getHexId() + handler.getNode().getHost();
        nodeHandlerMap.put(key, handler);
      }

      NodeList.Builder nodeListBuilder = NodeList.newBuilder();

      nodeHandlerMap.entrySet().stream()
          .forEach(v -> {
            org.tron.common.overlay.discover.node.Node node = v.getValue().getNode();
            nodeListBuilder.addNodes(Node.newBuilder().setAddress(
                Address.newBuilder()
                    .setHost(ByteString.copyFrom(ByteArray.fromString(node.getHost())))
                    .setPort(node.getPort())));
          });

      responseObserver.onNext(nodeListBuilder.build());
      responseObserver.onCompleted();
    }

    @Override
    public void transferAsset(TransferAssetContract request,
        StreamObserver<Transaction> responseObserver) {
      try {
        responseObserver
            .onNext(createTransactionCapsule(request, ContractType.TransferAssetContract)
                .getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug("ContractValidateException: {}", e.getMessage());
      }
      responseObserver.onCompleted();
    }

    @Override
    public void participateAssetIssue(ParticipateAssetIssueContract request,
        StreamObserver<Transaction> responseObserver) {
      try {
        responseObserver
            .onNext(createTransactionCapsule(request, ContractType.ParticipateAssetIssueContract)
                .getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug("ContractValidateException: {}", e.getMessage());
      }
      responseObserver.onCompleted();
    }

    @Override
    public void getAssetIssueByAccount(Account request,
        StreamObserver<AssetIssueList> responseObserver) {
      ByteString fromBs = request.getAddress();

      if (fromBs != null) {
        responseObserver.onNext(wallet.getAssetIssueByAccount(fromBs));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
    }

    @Override
    public void getAccountNet(Account request,
        StreamObserver<AccountNetMessage> responseObserver) {
      ByteString fromBs = request.getAddress();

      if (fromBs != null) {
        responseObserver.onNext(wallet.getAccountNet(fromBs));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
    }

    @Override
    public void getAssetIssueByName(BytesMessage request,
        StreamObserver<AssetIssueContract> responseObserver) {
      ByteString asertName = request.getValue();

      if (asertName != null) {
        responseObserver.onNext(wallet.getAssetIssueByName(asertName));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
    }

    @Override
    public void getBlockById(BytesMessage request, StreamObserver<Block> responseObserver) {
      ByteString blockId = request.getValue();

      if (Objects.nonNull(blockId)) {
        responseObserver.onNext(wallet.getBlockById(blockId));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
    }

    @Override
    public void getBlockByLimitNext(BlockLimit request,
        StreamObserver<BlockList> responseObserver) {
      long startNum = request.getStartNum();
      long endNum = request.getEndNum();

      if (endNum > 0 && endNum > startNum && endNum - startNum <= BLOCK_LIMIT_NUM) {
        responseObserver.onNext(wallet.getBlocksByLimitNext(startNum, endNum - startNum));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
    }

    @Override
    public void getBlockByLatestNum(NumberMessage request,
        StreamObserver<BlockList> responseObserver) {
      long getNum = request.getNum();

      if (getNum > 0 && getNum < BLOCK_LIMIT_NUM) {
        responseObserver.onNext(wallet.getBlockByLatestNum(getNum));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
    }

    @Override
    public void getTransactionById(BytesMessage request,
        StreamObserver<Transaction> responseObserver) {
      ByteString transactionId = request.getValue();

      if (Objects.nonNull(transactionId)) {
        responseObserver.onNext(wallet.getTransactionById(transactionId));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
    }

    @Override
    public void totalTransaction(EmptyMessage request,
        StreamObserver<NumberMessage> responseObserver) {
      responseObserver.onNext(wallet.totalTransaction());
      responseObserver.onCompleted();
    }

    @Override
    public void getNextMaintenanceTime(EmptyMessage request,
        StreamObserver<NumberMessage> responseObserver) {
      responseObserver.onNext(wallet.getNextMaintenanceTime());
      responseObserver.onCompleted();
    }

    @Override
    public void getAssetIssueList(EmptyMessage request,
        StreamObserver<AssetIssueList> responseObserver) {
      responseObserver.onNext(wallet.getAssetIssueList());
      responseObserver.onCompleted();
    }

    @Override
    public void listWitnesses(EmptyMessage request,
        StreamObserver<WitnessList> responseObserver) {
      responseObserver.onNext(wallet.getWitnessList());
      responseObserver.onCompleted();
    }
  }

  @Override
  public void stop() {
    if (apiServer != null) {
      apiServer.shutdown();
    }
  }

  /**
   * ...
   */
  public void blockUntilShutdown() {
    if (apiServer != null) {
      try {
        apiServer.awaitTermination();
      } catch (InterruptedException e) {
        logger.debug(e.getMessage(), e);
      }
    }
  }
}
