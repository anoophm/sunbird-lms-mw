package org.sunbird.user.actors;

import akka.actor.ActorRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.sunbird.actor.core.BaseActor;
import org.sunbird.actor.router.ActorConfig;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.ProjectLogger;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.common.models.util.ProjectUtil.Status;
import org.sunbird.common.models.util.PropertiesCache;
import org.sunbird.common.request.ExecutionContext;
import org.sunbird.common.request.Request;
import org.sunbird.common.responsecode.ResponseCode;
import org.sunbird.learner.util.Util;
import org.sunbird.models.user.User;
import org.sunbird.services.sso.SSOManager;
import org.sunbird.services.sso.SSOServiceFactory;
import org.sunbird.telemetry.util.TelemetryUtil;
import org.sunbird.user.dao.UserDao;
import org.sunbird.user.dao.impl.UserDaoImpl;
import org.sunbird.user.service.UserService;
import org.sunbird.user.service.impl.UserServiceImpl;

@ActorConfig(
  tasks = {"unblockUser", "blockUser"},
  asyncTasks = {}
)
public class UserStatusActor extends BaseActor {
  private UserDao userDao = UserDaoImpl.getInstance();
  private UserService userService = UserServiceImpl.getInstance();
  private SSOManager ssoManager = SSOServiceFactory.getInstance();
  private ObjectMapper mapper = new ObjectMapper();
  private boolean isSSOEnabled =
      Boolean.parseBoolean(PropertiesCache.getInstance().getProperty(JsonKey.IS_SSO_ENABLED));
  private ActorRef systemSettingActorRef =
      getActorRef(ActorOperations.GET_SYSTEM_SETTING.getValue());

  @Override
  public void onReceive(Request request) throws Throwable {
    Util.initializeContext(request, JsonKey.USER);
    ExecutionContext.setRequestId(request.getRequestId());
    String operation = request.getOperation();

    switch (operation) {
      case "blockUser":
        blockUser(request);
        break;

      case "unblockUser":
        unblockUser(request);
        break;

      default:
        onReceiveUnsupportedOperation("UserStatusActor");
    }
  }

  private void blockUser(Request actorMessage) {
    updateUserStatus(actorMessage, true);
  }

  private void unblockUser(Request actorMessage) {
    updateUserStatus(actorMessage, false);
  }

  private void updateUserStatus(Request request, boolean isBlocked) {
    String operation = request.getOperation();
    
    Util.getUserProfileConfig(systemSettingActorRef);
    String userId = (String) request.getRequest().get(JsonKey.USER_ID);
    String logMsgPrefix = MessageFormat.format("UserStatusActor:updateUserStatus:{0}:{1}: ", operation, userId);
    User user = userService.validateUserId(userId);

    if (operation.equals(ActorOperations.BLOCK_USER.getValue()) && user.getIsDeleted()) {
      throw new ProjectCommonException(
          ResponseCode.userAlreadyInactive.getErrorCode(),
          ResponseCode.userAlreadyInactive.getErrorMessage(),
          ResponseCode.CLIENT_ERROR.getResponseCode());
    }
    
    if (operation.equals(ActorOperations.UNBLOCK_USER.getValue()) && !user.getIsDeleted()) {
      throw new ProjectCommonException(
          ResponseCode.userAlreadyActive.getErrorCode(),
          ResponseCode.userAlreadyActive.getErrorMessage(),
          ResponseCode.CLIENT_ERROR.getResponseCode());
    }

    Map<String, Object> userMapES = getUserMapES(userId, (String) request.getContext().get(JsonKey.REQUESTED_BY), isBlocked);
    User updatedUser = mapper.convertValue(userMapES, User.class);
    
    if (isSSOEnabled) {
      if (isBlocked) {
        ssoManager.deactivateUser(userMapES);
      } else {
        ssoManager.activateUser(userMapES);
      }
    }

    Response response = userDao.updateUser(updatedUser);
    sender().tell(response, self());

    // Update status in ES
    if (((String) response.get(JsonKey.RESPONSE)).equalsIgnoreCase(JsonKey.SUCCESS)) {
      ProjectLogger.log(logMsgPrefix + "Update user data to ES.");
      
      Request userRequest = new Request();
      userRequest.setOperation(ActorOperations.UPDATE_USER_INFO_ELASTIC.getValue());
      userRequest.getRequest().put(JsonKey.ID, userId);

      try {
        tellToAnother(userRequest);
      } catch (Exception e) {
        ProjectLogger.log(logMsgPrefix + "Exception occurred with error message = " + e.getMessage(), e);
      }
    } else {
      ProjectLogger.log(logMsgPrefix + "Update user data to ES is skipped.");
    }

    generateTelemetry(userId, operation);
  }

  private Map<String, Object> getUserMapES(String userId, String updatedBy, boolean isDeleted) {
    Map<String, Object> esUserMap = new HashMap<>();
    
    esUserMap.put(JsonKey.IS_DELETED, isDeleted);
    esUserMap.put(JsonKey.STATUS, isDeleted ? Status.INACTIVE.getValue() : Status.ACTIVE.getValue());
    esUserMap.put(JsonKey.ID, userId);
    esUserMap.put(JsonKey.USER_ID, userId);
    esUserMap.put(JsonKey.UPDATED_DATE, ProjectUtil.getFormattedDate());
    esUserMap.put(JsonKey.UPDATED_BY, updatedBy);

    return esUserMap;
  }

  private void generateTelemetry(String userId, String objectType) {
    List<Map<String, Object>> correlatedObject = new ArrayList<>();
    Map<String, Object> targetObject =
        TelemetryUtil.generateTargetObject(userId, JsonKey.USER, JsonKey.UPDATE, null);
    Map<String, Object> telemetryAction = new HashMap<>();
    if (objectType.equalsIgnoreCase("blockUser")) {
      telemetryAction.put("BlockUser", "user blocked");
    } else {
      telemetryAction.put("UnblockUser", "user unblocked");
    }
    TelemetryUtil.telemetryProcessingCall(telemetryAction, targetObject, correlatedObject);
  }

}
