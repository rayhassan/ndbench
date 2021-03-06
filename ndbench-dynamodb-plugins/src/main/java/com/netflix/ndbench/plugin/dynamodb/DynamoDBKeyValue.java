/*
 *  Copyright 2018 Netflix, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.netflix.ndbench.plugin.dynamodb;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.retry.RetryPolicy;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughputExceededException;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazon.dax.client.dynamodbv2.AmazonDaxClientBuilder;
import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.BatchGetItemRequest;
import com.amazonaws.services.dynamodbv2.model.BatchGetItemResult;
import com.amazonaws.services.dynamodbv2.model.BatchWriteItemRequest;
import com.amazonaws.services.dynamodbv2.model.BatchWriteItemResult;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.DescribeTableRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.KeysAndAttributes;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.PutRequest;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.amazonaws.services.dynamodbv2.model.WriteRequest;
import com.amazonaws.services.dynamodbv2.util.TableUtils;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.netflix.ndbench.api.plugin.DataGenerator;
import com.netflix.ndbench.api.plugin.NdBenchClient;
import com.netflix.ndbench.api.plugin.annotations.NdBenchClientPlugin;
import com.netflix.ndbench.api.plugin.common.NdBenchConstants;
import com.netflix.ndbench.plugin.dynamodb.configs.DynamoDBConfigs;

import static com.amazonaws.retry.PredefinedRetryPolicies.DEFAULT_RETRY_CONDITION;
import static com.amazonaws.retry.PredefinedRetryPolicies.DYNAMODB_DEFAULT_BACKOFF_STRATEGY;
import static com.amazonaws.retry.PredefinedRetryPolicies.NO_RETRY_POLICY;

/**
 * This NDBench plugin provides a single key value for AWS DynamoDB.
 * 
 * @author ipapapa
 * @author Alexander Patrikalakis
 */
@Singleton
@NdBenchClientPlugin("DynamoDBKeyValue")
public class DynamoDBKeyValue implements NdBenchClient {
    private static final Logger logger = LoggerFactory.getLogger(DynamoDBKeyValue.class);
    private static final String ATTRIBUTE_NAME = "value";
    private static final boolean DO_HONOR_MAX_ERROR_RETRY_IN_CLIENT_CONFIG = true;

    private AmazonDynamoDB client;
    private AmazonDynamoDB daxClient;
    private AWSCredentialsProvider awsCredentialsProvider;
    private String partitionKeyName;

    private DynamoDBConfigs config;
    private DataGenerator dataGenerator;
    private String tableName;

    /**
     * Credentials will be loaded based on the environment. In AWS, the credentials
     * are based on the instance. In a local deployment they will have to provided.
     */
    @Inject
    public DynamoDBKeyValue(AWSCredentialsProvider credential, DynamoDBConfigs config) {
        this.config = config;
        String discoveryEnv = System.getenv(NdBenchConstants.DISCOVERY_ENV);
        logger.error("Discovery Environment Variable: " + discoveryEnv);
        if (discoveryEnv == null || discoveryEnv.equals(NdBenchConstants.DISCOVERY_ENV_AWS)) {
            awsCredentialsProvider = credential;
        } else {
            awsCredentialsProvider = new ProfileCredentialsProvider();
            try {
                awsCredentialsProvider.getCredentials();
            } catch (AmazonClientException ace) {
                throw new AmazonClientException("Cannot load the credentials from the credential profiles file. "
                    + "Please make sure that your credentials file is at the correct "
                    + "location (/home/<username>/.aws/credentials), and is in validformat.", ace);
            }
        }
    }

    @Override
    public void init(DataGenerator dataGenerator) {
        this.dataGenerator = dataGenerator;

        logger.info("Initing DynamoDBKeyValue plugin");
        AmazonDynamoDBClientBuilder builder = AmazonDynamoDBClientBuilder.standard();
        builder.withClientConfiguration(new ClientConfiguration()
                .withMaxConnections(config.getMaxConnections())
                .withRequestTimeout(config.getMaxRequestTimeout()) //milliseconds
                .withRetryPolicy(config.getMaxRetries() <= 0 ? NO_RETRY_POLICY : new RetryPolicy(DEFAULT_RETRY_CONDITION,
                        DYNAMODB_DEFAULT_BACKOFF_STRATEGY,
                        config.getMaxRetries(),
                        DO_HONOR_MAX_ERROR_RETRY_IN_CLIENT_CONFIG))
                .withGzip(config.isCompressing()));
        builder.withCredentials(awsCredentialsProvider);
        if (!Strings.isNullOrEmpty(this.config.getEndpoint())) {
            Preconditions.checkState(!Strings.isNullOrEmpty(config.getRegion()),
                    "If you set the endpoint you must set the region");
            builder.withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(config.getEndpoint(), config.getRegion()));
        }
        client = builder.build();

        if (this.config.programmableTables()) {
            logger.info("Creating table programmatically");
            initializeTable();
        }

        DescribeTableRequest describeTableRequest = new DescribeTableRequest()
            .withTableName(this.config.getTableName());
        TableDescription tableDescription = client.describeTable(describeTableRequest).getTable();
        logger.info("Table Description: " + tableDescription);

        if (this.config.isDax()) {
            logger.info("Using DAX");
            AmazonDaxClientBuilder amazonDaxClientBuilder = AmazonDaxClientBuilder.standard();
            amazonDaxClientBuilder.withEndpointConfiguration(this.config.getDaxEndpoint());
            client = amazonDaxClientBuilder.build();
        }
        tableName = config.getTableName();
        partitionKeyName = config.getAttributeName();

        logger.info("DynamoDB Plugin initialized");
    }

    /**
     * 
     * @param key
     * @return the item
     */
    @Override
    public String readSingle(String key) {
        final GetItemRequest request = new GetItemRequest()
                .withKey(ImmutableMap.of(partitionKeyName, new AttributeValue(key)))
                .withConsistentRead(config.consistentRead());
        final GetItemResult result;
        try {
            result = client.getItem(request); //will return null if the item does not exist.
            return Optional.ofNullable(result)
                    .map(GetItemResult::getItem)
                    .map(Map::toString)
                    .orElse(null);
        } catch (AmazonServiceException ase) {
            throw amazonServiceException(ase);
        } catch (AmazonClientException ace) {
            throw amazonClientException(ace);
        }
    }

    /**
     * 
     * @param key
     * @return A string representation of the output of a PutItemOutcome operation.
     */
    @Override
    public String writeSingle(String key) {
        try {
            final PutItemRequest request = new PutItemRequest()
                    .addItemEntry(partitionKeyName, new AttributeValue(key))
                    .addItemEntry(ATTRIBUTE_NAME, new AttributeValue(this.dataGenerator.getRandomValue()));
            // Write the item to the table
            return client.putItem(request).toString();
        } catch (AmazonServiceException ase) {
            throw amazonServiceException(ase);
        } catch (AmazonClientException ace) {
            throw amazonClientException(ace);
        }
    }

    @Override
    public List<String> readBulk(List<String> keys) throws Exception {
        Preconditions.checkArgument(new HashSet<>(keys).size() == keys.size());
        final KeysAndAttributes keysAndAttributes = generateReadRequests(keys);
        try {
            readUntilDone(keysAndAttributes);
            return keysAndAttributes.getKeys().stream()
                    .map(Map::toString)
                    .collect(Collectors.toList());
        } catch (AmazonServiceException ase) {
            throw amazonServiceException(ase);
        } catch (AmazonClientException ace) {
            throw amazonClientException(ace);
        }
    }

    @Override
    public List<String> writeBulk(List<String> keys) {
        Preconditions.checkArgument(new HashSet<>(keys).size() == keys.size());
        final List<WriteRequest> writeRequests = generateWriteRequests(keys);
        try {
            writeUntilDone(writeRequests);
            return writeRequests.stream()
                    .map(WriteRequest::getPutRequest)
                    .map(PutRequest::toString)
                    .collect(Collectors.toList());
        } catch (AmazonServiceException ase) {
            throw amazonServiceException(ase);
        } catch (AmazonClientException ace) {
            throw amazonClientException(ace);
        }
    }

    private List<WriteRequest> generateWriteRequests(List<String> keys) {
        return keys.stream()
                .map(key -> ImmutableMap.of(partitionKeyName, new AttributeValue(key),
                        ATTRIBUTE_NAME, new AttributeValue(this.dataGenerator.getRandomValue())))
                .map(item -> new PutRequest().withItem(item))
                .map(put -> new WriteRequest().withPutRequest(put))
                .collect(Collectors.toList());
    }

    private void writeUntilDone(List<WriteRequest> requests) {
        List<WriteRequest> remainingRequests = requests;
        BatchWriteItemResult result;
        do {
            result = runBatchWriteRequest(remainingRequests);
            remainingRequests = result.getUnprocessedItems().get(tableName);
        } while (remainingRequests!= null && remainingRequests.isEmpty());
    }

    private BatchWriteItemResult runBatchWriteRequest(List<WriteRequest> writeRequests) {
        //todo self throttle
        return client.batchWriteItem(new BatchWriteItemRequest().withRequestItems(
                ImmutableMap.of(tableName, writeRequests)));
    }

    private KeysAndAttributes generateReadRequests(List<String> keys) {
        return new KeysAndAttributes().withKeys(keys.stream()
                .map(key -> ImmutableMap.of("id", new AttributeValue(key)))
                .collect(Collectors.toList()));
    }

    private void readUntilDone(KeysAndAttributes keysAndAttributes) {
        KeysAndAttributes remainingKeys = keysAndAttributes;
        BatchGetItemResult result;
        do {
            keysAndAttributes.withConsistentRead(config.consistentRead());
            result = runBatchGetRequest(remainingKeys);
            remainingKeys = result.getUnprocessedKeys().get(tableName);
        } while (remainingKeys != null && remainingKeys.getKeys() != null && !remainingKeys.getKeys().isEmpty());
    }

    private BatchGetItemResult runBatchGetRequest(KeysAndAttributes keysAndAttributes) {
        //estimate size of requests
        //todo self throttle
        return client.batchGetItem(new BatchGetItemRequest().withRequestItems(
                ImmutableMap.of(tableName, keysAndAttributes)));
    }

    @Override
    public void shutdown() throws Exception {
        if (this.config.programmableTables()) {
            deleteTable();
        }
        client.shutdown();
        if (daxClient != null) {
            daxClient.shutdown();
        }
        logger.info("DynamoDB shutdown");
    }

    /*
     * Not needed for this plugin
     * 
     * @see com.netflix.ndbench.api.plugin.NdBenchClient#getConnectionInfo()
     */
    @Override
    public String getConnectionInfo() {
        return String.format("Table Name - %s : Attribute Name - %s : Consistent Read - %b", this.config.getTableName(),
            this.config.getAttributeName(), this.config.consistentRead());
    }

    @Override
    public String runWorkFlow() {
        return null;
    }

    private AmazonServiceException amazonServiceException(AmazonServiceException ase) {
        if ("ProvisionedThroughputExceededException".equals(ase.getErrorCode())) {
            logger.error("Caught an ProvisionedThroughputExceededException, which means your request made it "
                    + "to AWS, but was throttled because of consuming more capacity then provisioned.");
            //trim stack to reduce console output
            return new ProvisionedThroughputExceededException(ase.getMessage());
        } else {
            logger.error("Caught an AmazonServiceException, which means your request made it "
                    + "to AWS, but was rejected with an error response for some reason.");
            logger.error("Error Message:    " + ase.getMessage());
            logger.error("HTTP Status Code: " + ase.getStatusCode());
            logger.error("AWS Error Code:   " + ase.getErrorCode());
            logger.error("Error Type:       " + ase.getErrorType());
            logger.error("Request ID:       " + ase.getRequestId());
            return ase;
        }
    }

    private AmazonClientException amazonClientException(AmazonClientException ace) {
        logger.error("Caught an AmazonClientException, which means the client encountered "
            + "a serious internal problem while trying to communicate with AWS, "
            + "such as not being able to access the network.");
        logger.error("Error Message: " + ace.getMessage());
        return ace;
    }

    private void initializeTable() {
        /*
         * Create a table with a primary hash key named 'name', which holds a string.
         * Several properties such as provisioned throughput and atribute names are
         * defined in the configuration interface.
         */

        logger.debug("Creating table if it does not exist yet");

        Long readCapacityUnits = Long.parseLong(this.config.getReadCapacityUnits());
        Long writeCapacityUnits = Long.parseLong(this.config.getWriteCapacityUnits());

        // key schema
        ArrayList<KeySchemaElement> keySchema = new ArrayList<>();
        keySchema.add(new KeySchemaElement().withAttributeName(config.getAttributeName()).withKeyType(KeyType.HASH));

        // Attribute definitions
        ArrayList<AttributeDefinition> attributeDefinitions = new ArrayList<AttributeDefinition>();
        attributeDefinitions.add(new AttributeDefinition().withAttributeName(partitionKeyName)
            .withAttributeType(ScalarAttributeType.S));
        /*
         * constructing the table request: Schema + Attributed definitions + Provisioned
         * throughput
         */
        CreateTableRequest request = new CreateTableRequest().withTableName(this.config.getTableName())
            .withKeySchema(keySchema).withAttributeDefinitions(attributeDefinitions)
            .withProvisionedThroughput(new ProvisionedThroughput().withReadCapacityUnits(readCapacityUnits)
                .withWriteCapacityUnits(writeCapacityUnits));

        logger.info("Creating Table: " + this.config.getTableName());

        // Creating table
        if (TableUtils.createTableIfNotExists(client, request))
            logger.info("Table already exists.  No problem!");

        // Waiting util the table is ready
        try {
            logger.debug("Waiting until the table is in ACTIVE state");
            TableUtils.waitUntilActive(client, this.config.getTableName());
        } catch (AmazonClientException e) {
            logger.error("Table didn't become active: " + e);
        } catch (InterruptedException e) {
            logger.error("Table interrupted exception: " + e);
        }
    }

    private void deleteTable() {
        Table table = new Table(client, tableName);
        try {
            logger.info("Issuing DeleteTable request for " + config.getTableName());

            table.delete();

            logger.info("Waiting for " + config.getTableName() + " to be deleted...this may take a while...");

            table.waitForDelete();
        } catch (Exception e) {
            logger.error("DeleteTable request failed for " + config.getTableName());
            logger.error(e.getMessage());
        }
        table.delete(); // cleanup
    }
}
