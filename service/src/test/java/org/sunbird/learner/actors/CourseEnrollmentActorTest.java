package org.sunbird.learner.actors;

import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.when;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.javadsl.TestKit;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.cassandraimpl.CassandraOperationImpl;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.ProjectUtil.ProgressStatus;
import org.sunbird.common.request.Request;
import org.sunbird.common.responsecode.ResponseCode;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.learner.actors.coursebatch.CourseEnrollmentActor;
import org.sunbird.learner.util.EkStepRequestUtil;

/** @author arvind */
/** @author sudhirgiri */
@RunWith(PowerMockRunner.class)
@PrepareForTest({EkStepRequestUtil.class, ServiceFactory.class})
@PowerMockIgnore("javax.management.*")
public class CourseEnrollmentActorTest {

  private static ActorSystem system;
  private static final Props props = Props.create(CourseEnrollmentActor.class);
  private static CassandraOperation cassandraOperation;
  private static String userId = "someUserId";
  private static String batchId = "someBatchId";
  private static String courseId = "someCourseId";
  private static String id = "someid";
  private static String courseName = "someCourseName";
  private static String courseDescription = "someCourseDescription";
  private static String courseAppIcon = "somecourseAppIcon";
  private static SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");

  @BeforeClass
  public static void setUp() {
    system = ActorSystem.create("system");
    PowerMockito.mockStatic(EkStepRequestUtil.class);
  }

  @Before
  public void beforeEachTest() {
    PowerMockito.mockStatic(ServiceFactory.class);
    cassandraOperation = mock(CassandraOperationImpl.class);
    when(ServiceFactory.getInstance()).thenReturn(cassandraOperation);
    Mockito.reset(cassandraOperation);
  }

  @Test
  public void testEnrollCourseSuccessForStartedBatch() {
    Response response =
        getEnrollSuccessTestResponse(true, false, ProgressStatus.STARTED.getValue());
    Assert.assertTrue(null != response && response.getResponseCode() == ResponseCode.OK);
  }

  @Test
  public void testEnrollCourseSuccessForNotStartedBatch() {
    Response response =
        getEnrollSuccessTestResponse(true, false, ProgressStatus.NOT_STARTED.getValue());
    Assert.assertTrue(null != response && response.getResponseCode() == ResponseCode.OK);
  }

  @Test
  public void testEnrollCourseSuccessAfterUnenroll() {
    Response response =
        getEnrollSuccessTestResponse(false, false, ProgressStatus.STARTED.getValue());
    Assert.assertTrue(null != response && response.getResponseCode() == ResponseCode.OK);
  }

  @Test
  public void testEnrollCourseFailureWithAlreadyEnrolled() {
    ProjectCommonException exception =
        getEnrollFailureTestResponse(false, true, ProgressStatus.STARTED.getValue());
    Assert.assertTrue(
        null != exception
            && exception.getResponseCode() == ResponseCode.CLIENT_ERROR.getResponseCode());
  }

  @Test
  public void testEnrollCourseFailureWithBatchAlreadyCompleted() {
    ProjectCommonException exception =
        getEnrollFailureTestResponse(true, false, ProgressStatus.COMPLETED.getValue());
    Assert.assertTrue(
        null != exception
            && exception.getResponseCode() == ResponseCode.CLIENT_ERROR.getResponseCode());
  }

  @Test
  public void testUnenrollCourseSuccess() {
    Response response =
        getUnenrollSuccessTestResponse(false, true, ProgressStatus.STARTED.getValue());
    Assert.assertTrue(null != response && response.getResponseCode() == ResponseCode.OK);
  }

  @Test
  public void testUnenrollCourseFailureWithoutEnroll() {
    ProjectCommonException exception =
        getUnenrollFailureTestResponse(true, false, ProgressStatus.STARTED.getValue());
    Assert.assertTrue(
        null != exception
            && exception.getResponseCode() == ResponseCode.CLIENT_ERROR.getResponseCode());
  }

  @Test
  public void testUnenrollCourseFailureWithAlreadyUnenrolled() {
    ProjectCommonException exception =
        getUnenrollFailureTestResponse(false, false, ProgressStatus.STARTED.getValue());
    Assert.assertTrue(
        null != exception
            && exception.getResponseCode() == ResponseCode.CLIENT_ERROR.getResponseCode());
  }

  @Test
  public void testUnenrollCourseFailureWithCourseAlreadyCompleted() {
    ProjectCommonException exception =
        getUnenrollFailureTestResponse(false, false, ProgressStatus.COMPLETED.getValue());
    Assert.assertTrue(
        null != exception
            && exception.getResponseCode() == ResponseCode.CLIENT_ERROR.getResponseCode());
  }

  private Response getEnrollSuccessTestResponse(
      boolean isUserFirstTimeEnrolled, boolean userEnrollStatus, int batchStatus) {
    TestKit probe = new TestKit(system);
    ActorRef subject = system.actorOf(props);

    if (isUserFirstTimeEnrolled) {
      Response insertResponse = createCassandraInsertSuccessResponse();
      when(cassandraOperation.insertRecord(
              Mockito.anyString(), Mockito.anyString(), Mockito.anyMap()))
          .thenReturn(insertResponse);
    }
    mockCassandraRequestForReadRecordById(isUserFirstTimeEnrolled, userEnrollStatus, batchStatus);
    subject.tell(
        createRequest(userId, batchId, courseId, ActorOperations.ENROLL_COURSE.getValue()),
        probe.getRef());
    Response response = probe.expectMsgClass(Response.class);
    return response;
  }

  private ProjectCommonException getEnrollFailureTestResponse(
      boolean isUserFirstTimeEnrolled, boolean userEnrollStatus, int batchStatus) {
    TestKit probe = new TestKit(system);
    ActorRef subject = system.actorOf(props);

    mockCassandraRequestForReadRecordById(isUserFirstTimeEnrolled, userEnrollStatus, batchStatus);
    subject.tell(
        createRequest(userId, batchId, courseId, ActorOperations.ENROLL_COURSE.getValue()),
        probe.getRef());
    ProjectCommonException exception = probe.expectMsgClass(ProjectCommonException.class);
    return exception;
  }

  private Response getUnenrollSuccessTestResponse(
      boolean isUserFirstTimeEnrolled, boolean userEnrollStatus, int batchStatus) {
    TestKit probe = new TestKit(system);
    ActorRef subject = system.actorOf(props);

    mockCassandraRequestForReadRecordById(isUserFirstTimeEnrolled, userEnrollStatus, batchStatus);
    subject.tell(
        createRequest(userId, batchId, courseId, ActorOperations.UNENROLL_COURSE.getValue()),
        probe.getRef());
    Response response = probe.expectMsgClass(Response.class);
    return response;
  }

  private ProjectCommonException getUnenrollFailureTestResponse(
      boolean isUserFirstTimeEnrolled, boolean userEnrollStatus, int batchStatus) {
    TestKit probe = new TestKit(system);
    ActorRef subject = system.actorOf(props);

    mockCassandraRequestForReadRecordById(isUserFirstTimeEnrolled, userEnrollStatus, batchStatus);
    subject.tell(
        createRequest(userId, batchId, courseId, ActorOperations.UNENROLL_COURSE.getValue()),
        probe.getRef());
    ProjectCommonException exception = probe.expectMsgClass(ProjectCommonException.class);
    return exception;
  }

  private Response createCassandraInsertSuccessResponse() {
    Response response = new Response();
    response.put(JsonKey.RESPONSE, JsonKey.SUCCESS);
    return response;
  }

  private void mockCassandraRequestForReadRecordById(
      boolean isUserFirstTimeEnrolled, boolean userEnrollStatus, int batchStatus) {
    if (isUserFirstTimeEnrolled)
      when(cassandraOperation.getRecordById(
              Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
          .thenReturn(
              createGetCourseBatchSuccessResponse(batchStatus),
              createGetUserCourseFailureResponse());
    else
      when(cassandraOperation.getRecordById(
              Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
          .thenReturn(
              createGetCourseBatchSuccessResponse(batchStatus),
              createGetUserCourseSuccessResponse(userEnrollStatus, batchStatus));
  }

  private Response createGetUserCourseFailureResponse() {
    Response response = new Response();
    List<Map<String, Object>> result = new ArrayList<>();
    response.put(JsonKey.RESPONSE, result);
    return response;
  }

  private Response createGetUserCourseSuccessResponse(boolean active, int batchStatus) {
    Response response = new Response();
    Map<String, Object> userMap = new HashMap<>();
    userMap.put(JsonKey.USER_ID, userId);
    userMap.put(JsonKey.BATCH_ID, batchId);
    userMap.put(JsonKey.COURSE_ID, courseId);
    userMap.put(JsonKey.ACTIVE, active);
    userMap.put(JsonKey.PROGRESS, batchStatus);
    userMap.put(JsonKey.ID, id);
    List<Map<String, Object>> result = new ArrayList<>();
    result.add(userMap);
    response.put(JsonKey.RESPONSE, result);
    return response;
  }

  private Response createGetCourseBatchSuccessResponse(int status) {
    Response response = new Response();
    Map<String, Object> userMap = new HashMap<>();
    userMap.put(JsonKey.COURSE_ID, courseId);
    userMap.put(JsonKey.ID, batchId);
    userMap.put(JsonKey.STATUS, status);
    userMap.put(JsonKey.START_DATE, calculateDate(0));
    userMap.put(JsonKey.END_DATE, calculateDate(4));
    Map<String, Object> courseInfo = new HashMap<>();
    courseInfo.put(JsonKey.NAME, courseName);
    courseInfo.put(JsonKey.DESCRIPTION, courseDescription);
    courseInfo.put(JsonKey.APP_ICON, courseAppIcon);
    userMap.put(JsonKey.COURSE_ADDITIONAL_INFO, courseInfo);
    List<Map<String, Object>> result = new ArrayList<>();
    result.add(userMap);
    response.put(JsonKey.RESPONSE, result);
    return response;
  }

  public Request createRequest(String userId, String batchId, String courseId, String operation) {
    Request actorMessage = new Request();
    HashMap<String, Object> innerMap = new HashMap<>();
    innerMap.put(JsonKey.REQUESTED_BY, userId);
    actorMessage.setContext(innerMap);
    actorMessage.setOperation(operation);
    HashMap<String, Object> requestBody = new HashMap<>();
    requestBody.put(JsonKey.BATCH_ID, batchId);
    requestBody.put(JsonKey.USER_ID, userId);
    requestBody.put(JsonKey.COURSE_ID, courseId);
    actorMessage.setRequest(requestBody);

    return actorMessage;
  }

  private String calculateDate(int dayOffset) {

    Calendar calender = Calendar.getInstance();
    calender.add(Calendar.DAY_OF_MONTH, dayOffset);
    return format.format(calender.getTime());
  }
}
