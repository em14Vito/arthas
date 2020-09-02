package com.alibaba.arthas.channel.server.service.impl;

import com.alibaba.arthas.channel.proto.ActionRequest;
import com.alibaba.arthas.channel.proto.ActionResponse;
import com.alibaba.arthas.channel.proto.ConsoleParams;
import com.alibaba.arthas.channel.proto.ExecuteParams;
import com.alibaba.arthas.channel.proto.ExecuteResult;
import com.alibaba.arthas.channel.proto.RequestAction;
import com.alibaba.arthas.channel.proto.ResponseStatus;
import com.alibaba.arthas.channel.proto.ResultFormat;
import com.alibaba.arthas.channel.server.api.ApiAction;
import com.alibaba.arthas.channel.server.api.ApiRequest;
import com.alibaba.arthas.channel.server.api.ApiResponse;
import com.alibaba.arthas.channel.server.api.ApiState;
import com.alibaba.arthas.channel.server.message.MessageExchangeException;
import com.alibaba.arthas.channel.server.message.MessageExchangeService;
import com.alibaba.arthas.channel.server.message.topic.ActionRequestTopic;
import com.alibaba.arthas.channel.server.message.topic.ActionResponseTopic;
import com.alibaba.arthas.channel.server.service.AgentManageService;
import com.alibaba.arthas.channel.server.service.ApiActionDelegateService;
import com.google.protobuf.StringValue;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import org.apache.commons.lang3.RandomStringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @author gongdewei 2020/8/11
 */
public class ApiActionDelegateServiceImpl implements ApiActionDelegateService {

    private static final Logger logger = LoggerFactory.getLogger(ApiActionDelegateServiceImpl.class);

    @Autowired
    private AgentManageService agentManageService;

    @Autowired
    private MessageExchangeService messageExchangeService;


    @Override
    public Promise<ApiResponse> initSession(String agentId) throws Exception {
        ApiRequest apiRequest = new ApiRequest();
        apiRequest.setAction(ApiAction.INIT_SESSION.name());
        return sendRequestAndSubscribe(agentId, apiRequest);
    }

//    @Override
//    public Promise<ApiResponse> joinSession(String agentId, String sessionId) throws Exception {
//        ApiRequest apiRequest = new ApiRequest();
//        apiRequest.setAction(ApiAction.JOIN_SESSION.name());
//        apiRequest.setSessionId(sessionId);
//        return sendRequestAndSubscribe(agentId, apiRequest);
//    }

    @Override
    public Promise<ApiResponse> closeSession(String agentId, String sessionId) throws Exception {
        ApiRequest apiRequest = new ApiRequest();
        apiRequest.setAction(ApiAction.CLOSE_SESSION.name());
        apiRequest.setSessionId(sessionId);
        return sendRequestAndSubscribe(agentId, apiRequest);
    }

    @Override
    public Promise<ApiResponse> interruptJob(String agentId, String sessionId) throws Exception {
        ApiRequest apiRequest = new ApiRequest();
        apiRequest.setAction(ApiAction.INTERRUPT_JOB.name());
        apiRequest.setSessionId(sessionId);
        return sendRequestAndSubscribe(agentId, apiRequest);
    }

    /**
     * Send request and get results with promise.
     *
     * NOTE: This method do not support streaming command results, all results are received after the command is executed.
     * @param agentId
     * @param request
     * @return
     */
    @Override
    public Promise<ApiResponse> execCommand(String agentId, ApiRequest request) throws Exception {
        return sendRequestAndSubscribe(agentId, request);
    }

    /**
     * Send request and process command results with responseListener.
     * Support streaming command results.
     * @param agentId
     * @param request
     * @return
     */
    @Override
    public ApiResponse asyncExecCommand(final String agentId, ApiRequest request) throws Exception {
        //send request
        String requestId = generateRandomRequestId();
        sendRequest(agentId, requestId, request);

        //TODO 获取JobId？
        return new ApiResponse()
                .setState(ApiState.CONTINUOUS)
                .setRequestId(requestId);
    }

    @Override
    public Promise<ActionResponse> openConsole(String agentId, int timeout) throws Exception {
        //send request
        String requestId = generateRandomRequestId();
        ActionRequest actionRequest = ActionRequest.newBuilder()
                .setAgentId(agentId)
                .setRequestId(requestId)
                .setAction(RequestAction.OPEN_CONSOLE)
                .build();
        messageExchangeService.pushMessage(new ActionRequestTopic(agentId), actionRequest.toByteArray());

        //subscribe response
        return subscribeResponse(agentId, requestId, timeout);
    }

    @Override
    public void consoleInput(String agentId, String consoleId, String inputData) throws Exception {
        //send request
        ActionRequest actionRequest = ActionRequest.newBuilder()
                .setAgentId(agentId)
                .setRequestId(consoleId)
                .setAction(RequestAction.CONSOLE_INPUT)
                .setConsoleParams(ConsoleParams.newBuilder()
                        .setConsoleId(consoleId)
                        .setInputData(inputData))
                .build();

        messageExchangeService.pushMessage(new ActionRequestTopic(agentId), actionRequest.toByteArray());
    }

    @Override
    public void closeConsole(String agentId, String consoleId) throws Exception {
        //send request
        ActionRequest actionRequest = ActionRequest.newBuilder()
                .setAgentId(agentId)
                .setRequestId(consoleId)
                .setAction(RequestAction.CLOSE_CONSOLE)
                .setConsoleParams(ConsoleParams.newBuilder()
                        .setConsoleId(consoleId))
                .build();
        messageExchangeService.pushMessage(new ActionRequestTopic(agentId), actionRequest.toByteArray());
    }

    @Override
    public ApiResponse pullResults(final String agentId, String requestId, int timeout) throws Exception {
        //subscribe response
        ActionResponseTopic topic = new ActionResponseTopic(agentId, requestId);
        byte[] messageBytes = messageExchangeService.pollMessage(topic, timeout);
        if (messageBytes == null) {
            return new ApiResponse()
                    .setAgentId(agentId)
                    .setRequestId(requestId)
                    .setState(ApiState.FAILED)
                    .setMessage("Timeout");
        }

        try {
            ActionResponse actionResponse = ActionResponse.parseFrom(messageBytes);
            ApiResponse apiResponse = convertApiResponse(actionResponse);
            return apiResponse;
        } catch (Throwable e) {
            logger.error("process action response message failure", e);
            return new ApiResponse()
                    .setAgentId(agentId)
                    .setRequestId(requestId)
                    .setState(ApiState.FAILED)
                    .setMessage("process action response message failure");
        }
    }

    @Override
    public void subscribeResults(final String agentId, final String requestId, int timeout, final ResponseListener responseListener) throws Exception {
        //subscribe response
        ActionResponseTopic topic = new ActionResponseTopic(agentId, requestId);

        messageExchangeService.subscribe(topic, timeout, new MessageExchangeService.MessageHandler() {
            @Override
            public boolean onMessage(byte[] messageBytes) {
                ActionResponse actionResponse;
                try {
                    actionResponse = ActionResponse.parseFrom(messageBytes);
                } catch (Throwable e) {
                    logger.error("process action response message failure", e);
                    actionResponse = ActionResponse.newBuilder()
                            .setAgentId(agentId)
                            .setRequestId(requestId)
                            .setStatus(ResponseStatus.FAILED)
                            .setMessage(StringValue.of("process action response message failure"))
                            .build();
                }
                boolean next = responseListener.onMessage(actionResponse);
                return next && actionResponse.getStatus().equals(ResponseStatus.CONTINUOUS);
            }

            @Override
            public void onTimeout() {

            }
        });
    }

    /**
     * Send one-time request and subscribe it's response.
     * NOTE: This method do not support streaming results.
     * @param agentId
     * @param request
     * @return
     */
    private Promise<ApiResponse> sendRequestAndSubscribe(String agentId, ApiRequest request) throws Exception {
        //send request
        String requestId = generateRandomRequestId();
        sendRequest(agentId, requestId, request);

        //subscribe response
        return subscribeResponse1(agentId, requestId, request.getExecTimeout());
    }

    private Promise<ApiResponse> subscribeResponse1(String agentId, String requestId, Integer timeout) throws MessageExchangeException {
        final Promise<ApiResponse> promise = GlobalEventExecutor.INSTANCE.newPromise();
        int execTimeout = 30000;
        if (timeout != null && timeout > 0) {
            execTimeout = timeout;
        }
        final ActionResponseTopic responseTopic = new ActionResponseTopic(agentId, requestId);
        messageExchangeService.subscribe(responseTopic, execTimeout, new MessageExchangeService.MessageHandler() {
            @Override
            public boolean onMessage(byte[] messageBytes) {
                try {
                    ActionResponse actionResponse = ActionResponse.parseFrom(messageBytes);
                    ApiResponse apiResponse = convertApiResponse(actionResponse);

                    promise.setSuccess(apiResponse);
                } catch (Throwable e) {
                    logger.error("process response message failure: "+e.getMessage(), e);
                    promise.setSuccess(new ApiResponse()
                            .setState(ApiState.FAILED)
                            .setMessage("process response message failure: "+e.getMessage()));
                }

                //promise is one-time subscribe, just remove it after received message
                try {
                    messageExchangeService.removeTopic(responseTopic);
                } catch (Throwable e) {
                    logger.error("remove topic failure", e);
                }
                return false;
            }

            @Override
            public void onTimeout() {
                promise.setSuccess(new ApiResponse()
                        .setState(ApiState.FAILED)
                        .setMessage("Timeout"));
            }
        });
        return promise;
    }

    private Promise<ActionResponse> subscribeResponse(String agentId, String requestId, Integer timeout) throws MessageExchangeException {
        final Promise<ActionResponse> promise = GlobalEventExecutor.INSTANCE.newPromise();
        int execTimeout = 30000;
        if (timeout != null && timeout > 0) {
            execTimeout = timeout;
        }
        final ActionResponseTopic responseTopic = new ActionResponseTopic(agentId, requestId);
        messageExchangeService.subscribe(responseTopic, execTimeout, new MessageExchangeService.MessageHandler() {
            @Override
            public boolean onMessage(byte[] messageBytes) {
                try {
                    ActionResponse actionResponse = ActionResponse.parseFrom(messageBytes);

                    promise.setSuccess(actionResponse);
                } catch (Throwable e) {
                    logger.error("process response message failure: "+e.getMessage(), e);
                    promise.setSuccess(ActionResponse.newBuilder()
                            .setStatus(ResponseStatus.FAILED)
                            .setMessage(StringValue.of("process response message failure: "+e.getMessage()))
                            .build());
                }

                //promise is one-time subscribe, just remove it after received message
                try {
                    messageExchangeService.removeTopic(responseTopic);
                } catch (Throwable e) {
                    logger.error("remove topic failure", e);
                }
                return false;
            }

            @Override
            public void onTimeout() {
                promise.setSuccess(ActionResponse.newBuilder()
                        .setStatus(ResponseStatus.FAILED)
                        .setMessage(StringValue.of("timeout"))
                        .build());
            }
        });
        return promise;
    }

    private void sendRequest(String agentId, String requestId, ApiRequest request) throws Exception {
        final RequestAction action = getAction(request.getAction());

        ActionRequest.Builder actionRequestBuilder = ActionRequest.newBuilder()
                .setAgentId(agentId)
                .setRequestId(requestId)
                .setAction(action);

        if (request.getSessionId() != null) {
            actionRequestBuilder = actionRequestBuilder.setSessionId(StringValue.of(request.getSessionId()));
        }
//        if (request.getConsumerId() != null) {
//            actionRequestBuilder = actionRequestBuilder.setConsumerId(StringValue.of(request.getConsumerId()));
//        }
        if (request.getCommand() != null) {
            int execTimeout = request.getExecTimeout() !=null && request.getExecTimeout() > 0 ? request.getExecTimeout() : 30000;
            actionRequestBuilder = actionRequestBuilder.setExecuteParams(ExecuteParams.newBuilder()
                    .setResultFormat(ResultFormat.JSON)
                    .setCommandLine(request.getCommand())
                    .setExecTimeout(execTimeout)
                    .build());
        }
        ActionRequest actionRequest = actionRequestBuilder.build();
        messageExchangeService.pushMessage(new ActionRequestTopic(agentId), actionRequest.toByteArray());
    }

    private ApiResponse convertApiResponse(ActionResponse actionResponse) {
        ApiResponse apiResponse = new ApiResponse()
                .setState(getState(actionResponse.getStatus()))
                .setAgentId(actionResponse.getAgentId())
                .setRequestId(actionResponse.getRequestId());

        if (actionResponse.hasSessionId()) {
            apiResponse.setSessionId(actionResponse.getSessionId().getValue());
        }
//        if (actionResponse.hasConsumerId()) {
//            apiResponse.setConsumerId(actionResponse.getConsumerId().getValue());
//        }
        if (actionResponse.hasMessage()) {
            apiResponse.setMessage(actionResponse.getMessage().getValue());
        }
        if (actionResponse.hasExecuteResult()) {
            ExecuteResult executeResult = actionResponse.getExecuteResult();
            if (executeResult.hasResultsJson()) {
                apiResponse.setResult(executeResult.getResultsJson().getValue());
            }
        }
        return apiResponse;
    }

    private RequestAction getAction(String action) {
        ApiAction apiAction = ApiAction.valueOf(action.trim().toUpperCase());

        switch (apiAction) {
            case EXEC:
                return RequestAction.EXECUTE;
            case ASYNC_EXEC:
                return RequestAction.ASYNC_EXECUTE;
//            case JOIN_SESSION:
//                return RequestAction.JOIN_SESSION;
            case INIT_SESSION:
                return RequestAction.INIT_SESSION;
            case CLOSE_SESSION:
                return RequestAction.CLOSE_SESSION;
            case INTERRUPT_JOB:
                return RequestAction.INTERRUPT_JOB;
            case OPEN_CONSOLE:
                return RequestAction.OPEN_CONSOLE;
            case CONSOLE_INPUT:
                return RequestAction.CONSOLE_INPUT;
        }
        throw new IllegalArgumentException("Unsupported request action: " + action);
    }

    private String generateRandomRequestId() {
        //return UUID.randomUUID().toString().replaceAll("-", "");
        return RandomStringUtils.random(12, true, true);
    }

    private ApiState getState(ResponseStatus status) {
        switch (status) {
            case SUCCEEDED:
                return ApiState.SUCCEEDED;
            case REFUSED:
                return ApiState.REFUSED;
            case CONTINUOUS:
                return ApiState.CONTINUOUS;
            case INTERRUPTED:
                return ApiState.INTERRUPTED;

            case FAILED:
            case UNRECOGNIZED:
                return ApiState.FAILED;
        }
        return ApiState.FAILED;
    }


}
