/*
 *  Copyright 2016 DTCC, Fujitsu Australia Software Technology, IBM - All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.hyperledger.fabric.sdk;

import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

import org.apache.commons.compress.utils.IOUtils;
import org.hyperledger.fabric.sdk.events.EventHub;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Test end to end scenario
 */
public class End2endIT {

    static final String CHAIN_CODE_NAME = "example_cc.go";
    static final String CHAIN_CODE_PATH = "github.com/example_cc";
    static final String CHAIN_CODE_VERSION = "1.0";


    static final String CHAIN_NAME = "testchainid";

    final static Collection<String> PEER_LOCATIONS = Arrays.asList("grpc://localhost:7051");


    final static Collection<String> ORDERER_LOCATIONS = Arrays.asList("grpc://localhost:7050"); //Vagrant maps to this

    final static Collection<String> EVENTHUB_LOCATIONS = Arrays.asList("grpc://localhost:7053"); //Vagrant maps to this

    final static String FABRIC_CA_SERVICES_LOCATION = "http://localhost:7054";

    private TestConfigHelper configHelper = new TestConfigHelper() ;

    @Before
    public void checkConfig() throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
        configHelper.clearConfig();
        configHelper.customizeConfig();
    }

    @After
    public void clearConfig() {
        try { configHelper.clearConfig();} catch (Exception e) {};
    }

    @Test
    public void setup() {

        HFClient client = HFClient.createNewInstance();
        try {

            //////////////////////////// TODo Needs to be made out of bounds and here chain just retrieved
            //Construct the chain
            //

            constructChain(client);

            client.setUserContext(new User("admin")); // User will be defined by pluggable

            Chain chain = client.getChain(CHAIN_NAME);


            chain.setInvokeWaitTime(1000);
            chain.setDeployWaitTime(12000);

            chain.setMemberServicesUrl(FABRIC_CA_SERVICES_LOCATION, null);

            File fileStore = new File(System.getProperty("user.home") + "/test.properties");
            if (fileStore.exists()) {
                fileStore.delete();
            }
            chain.setKeyValStore(new FileKeyValStore(fileStore.getAbsolutePath()));
            chain.enroll("admin", "adminpw");

            chain.initialize();

            Collection<Peer> peers = chain.getPeers();
            Collection<Orderer> orderers = chain.getOrderers();

            ////////////////////////////
            //Install Proposal Request
            //

            out("Creating install proposal");

            InstallProposalRequest installProposalRequest = client.newInstallProposalRequest();
            installProposalRequest.setChaincodeName(CHAIN_CODE_NAME);
            installProposalRequest.setChaincodePath(CHAIN_CODE_PATH);
            installProposalRequest.setChaincodeVersion(CHAIN_CODE_VERSION);


            Collection<ProposalResponse> responses = chain.sendInstallProposal(installProposalRequest, peers);


            Collection<ProposalResponse> successful = new LinkedList<>();
            Collection<ProposalResponse> failed = new LinkedList<>();


            for (ProposalResponse response : responses) {
                if (response.isVerified() && response.getStatus() == ProposalResponse.Status.SUCCESS) {
                    successful.add(response);

                } else {
                    failed.add(response);
                }

            }
            out("Received %d install proposal responses. Successful+verified: %d . Failed: %d", responses.size(), successful.size(), failed.size());

            if (successful.size() < 1) {  //choose this as an arbitrary limit right now.

                if (failed.size() == 0) {
                    throw new Exception("No endorsers found ");

                }
                ProposalResponse first = failed.iterator().next();

                throw new Exception("Not enough endorsers for install :" + successful.size() + ".  " + first.getMessage());
            }
            ProposalResponse firstInstallProposalResponse = successful.iterator().next();
            final ChainCodeID chainCodeID = firstInstallProposalResponse.getChainCodeID();
            //Note install chain code does not require transaction no need to send to Orderers

            ///////////////
            //// Instantiate chain code.


            InstantiateProposalRequest instantiateProposalRequest = client.newInstantiationProposalRequest();


            instantiateProposalRequest.setChaincodeID(chainCodeID);
            instantiateProposalRequest.setFcn("init");
            instantiateProposalRequest.setArgs(new String[]{"a", "100", "b", "200"});

            ChaincodeEndorsementPolicy chaincodeEndorsementPolicy = new ChaincodeEndorsementPolicy(new File("src/test/resources/policyBitsAdmin"));
            instantiateProposalRequest.setChaincodeEndorsementPolicy(chaincodeEndorsementPolicy);

            out("Sending instantiateProposalRequest code with a and b set to 100 and 200 respectively");

            responses = chain.sendInstantiationProposal(instantiateProposalRequest, peers);

            successful.clear();
            failed.clear();

            for (ProposalResponse response : responses) {
                if (response.isVerified() && response.getStatus() == ProposalResponse.Status.SUCCESS) {
                    successful.add(response);

                } else {
                    failed.add(response);
                }

            }
            out("Received %d instantiate proposal responses. Successful+verified: %d . Failed: %d", responses.size(), successful.size(), failed.size());

            if (successful.size() < 1) {  //choose this as an arbitrary limit right now.

                if (failed.size() == 0) {
                    throw new Exception("No endorsers found ");

                }
                ProposalResponse first = failed.iterator().next();

                throw new Exception("Not enough endorsers for instantiate  :" + successful.size() + ".  " + first.getMessage());
            }


            /// Send instantiate transaction.
            chain.sendTransaction(successful, orderers).thenApply(block -> {

                try {

                    out("Successfully completed chaincode instantiation.");

                    out("Creating invoke proposal");

                    InvokeProposalRequest invokeProposalRequest = client.newInvokeProposalRequest();

                    invokeProposalRequest.setChaincodeID(chainCodeID);
                    invokeProposalRequest.setFcn("invoke");
                    invokeProposalRequest.setArgs(new String[]{"move", "a", "b", "100"});

                    Collection<ProposalResponse> invokePropResp = chain.sendInvokeProposal(invokeProposalRequest, peers);


                    successful.clear();
                    failed.clear();

                    for (ProposalResponse response : invokePropResp) {

                        if (response.getStatus() == ProposalResponse.Status.SUCCESS) {
                            successful.add(response);
                        } else {
                            failed.add(response);
                        }

                    }
                    out("Received %d invoke proposal responses. Successful+verified: %d . Failed: %d", invokePropResp.size(), successful.size(), failed.size());


                    if (successful.size() < 1) {  //choose this as an arbitrary limit right now.

                        if (failed.size() == 0) {
                            throw new Exception("No endorsers found ");

                        }
                        ProposalResponse firstInvokeProposalResponse = failed.iterator().next();


                        throw new Exception("Not enough endorsers :" + successful.size() + ".  " + firstInvokeProposalResponse.getMessage());


                    }
                    out("Successfully received invoke proposal response.");

                    ////////////////////////////
                    // Invoke Transaction
                    //

                    out("Invoking chain code transaction to move 100 from a to b.");

                    return chain.sendTransaction(successful, orderers).get(120, TimeUnit.SECONDS);


                } catch (Exception e) {

                    throw new RuntimeException(e);

                }


            }).thenApply(block -> {
                try {
                    out("Successfully ordered invoke chain code. BlockClass" + block.getClass());


                    ////////////////////////////
                    // Query Proposal
                    //


                    out("Now query chain code for the value of b.");


                    // InvokeProposalRequest qr = InvokeProposalRequest.newInstance();
                    QueryProposalRequest queryProposalRequest = client.newQueryProposalRequest();

                    queryProposalRequest.setArgs(new String[]{"query", "b"});
                    queryProposalRequest.setFcn("invoke");
                    queryProposalRequest.setChaincodeID(chainCodeID);


                    Collection<ProposalResponse> queryProposals = chain.sendQueryProposal(queryProposalRequest, peers);

                    for (ProposalResponse proposalResponse : queryProposals) {
                        if (!proposalResponse.isVerified() || proposalResponse.getStatus() != ProposalResponse.Status.SUCCESS) {
                            return new Exception("Failed invoke proposal.  status: " + proposalResponse.getStatus() + ". messages: " + proposalResponse.getMessage());

                        }

                    }

                    out("Successfully received query response.");

                    String payload = queryProposals.iterator().next().getProposalResponse().getResponse().getPayload().toStringUtf8();

                    out("Query payload of b returned %s", payload);


                    Assert.assertEquals(payload, "300");

                    if (!payload.equals("300")) {
                        return new Exception("Expected 300 for value b but got: " + payload);
                    }


                    return null;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

            }).exceptionally(e -> {
                System.err.println("Bad status value for proposals transaction: " + e.getMessage());
                System.exit(8);
                return null;
            }).get(120, TimeUnit.SECONDS);
            out("That's all folks!");


        } catch (Exception e) {
            out("Caught an exception");
            e.printStackTrace();

            Assert.fail(e.getMessage());

        }

    }


    private static void constructChain(HFClient client) throws Exception {
        //////////////////////////// TODo Needs to be made out of bounds and here chain just retrieved
        //Construct the chain
        //

        Chain newChain = client.newChain(CHAIN_NAME);

        for (String peerloc : PEER_LOCATIONS) {
            Peer peer = client.newPeer(peerloc);
            peer.setName("peer1");
            newChain.addPeer(peer);
        }

        for (String orderloc : ORDERER_LOCATIONS) {
            Orderer orderer = client.newOrderer(orderloc);
            newChain.addOrderer(orderer);
        }

        for (String eventHub : EVENTHUB_LOCATIONS) {
            EventHub orderer = client.newEventHub(eventHub);
            newChain.addEventHub(orderer);
        }

    }


    static void out(String format, Object... args) {

        System.err.flush();
        System.out.flush();

        System.out.println(String.format(format, args));
        System.err.flush();
        System.out.flush();

    }

}
