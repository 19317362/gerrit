import static org.junit.Assert.assertEquals;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
  public static final String PATCH_FILE_ONLY =
      "diff --git a/a.txt b/a.txt\n"
          + "new file mode 100644\n"
          + "index 0000000..f0eec86\n"
          + "--- /dev/null\n"
          + "+++ b/a.txt\n"
          + "@@ -0,0 +1 @@\n"
          + "+some content\n"
          + "\\ No newline at end of file\n";
      "From %s Mon Sep 17 00:00:00 2001\n"
          + "From: Administrator <admin@example.com>\n"
          + "Date: %s\n"
          + "Subject: [PATCH] test commit\n"
          + "\n"
          + "Change-Id: %s\n"
          + "---\n"
          + "\n"
          + PATCH_FILE_ONLY;
    PushOneCommit create(ReviewDb db, PersonIdent i, TestRepository<?> testRepo);
  private static AtomicInteger CHANGE_ID_COUNTER = new AtomicInteger();

  private static String nextChangeId() {
    // Tests use a variety of mechanisms for setting temporary timestamps, so we can't guarantee
    // that the PersonIdent (or any other field used by the Change-Id generator) for any two test
    // methods in the same acceptance test class are going to be different. But tests generally
    // assume that Change-Ids are unique unless otherwise specified. So, don't even bother trying to
    // reuse JGit's Change-Id generator, just do the simplest possible thing and convert a counter
    // to hex.
    return String.format("%040x", CHANGE_ID_COUNTER.incrementAndGet());
  }

  private List<String> pushOptions;
  PushOneCommit(
      ChangeNotes.Factory notesFactory,
      @Assisted TestRepository<?> testRepo)
      throws Exception {
    this(
        notesFactory,
        approvalsUtil,
        queryProvider,
        db,
        i,
        testRepo,
        SUBJECT,
        FILE_NAME,
        FILE_CONTENT);
  PushOneCommit(
      ChangeNotes.Factory notesFactory,
      @Assisted("changeId") String changeId)
      throws Exception {
    this(
        notesFactory,
        approvalsUtil,
        queryProvider,
        db,
        i,
        testRepo,
        SUBJECT,
        FILE_NAME,
        FILE_CONTENT,
        changeId);
  PushOneCommit(
      ChangeNotes.Factory notesFactory,
      @Assisted("content") String content)
      throws Exception {
    this(
        notesFactory,
        approvalsUtil,
        queryProvider,
        db,
        i,
        testRepo,
        subject,
        fileName,
        content,
        null);
  PushOneCommit(
      ChangeNotes.Factory notesFactory,
      @Assisted Map<String, String> files)
      throws Exception {
    this(notesFactory, approvalsUtil, queryProvider, db, i, testRepo, subject, files, null);
  PushOneCommit(
      ChangeNotes.Factory notesFactory,
      @Nullable @Assisted("changeId") String changeId)
      throws Exception {
    this(
        notesFactory,
        approvalsUtil,
        queryProvider,
        db,
        i,
        testRepo,
        subject,
        ImmutableMap.of(fileName, content),
        changeId);
  private PushOneCommit(
      ChangeNotes.Factory notesFactory,
      String changeId)
      throws Exception {
      commitBuilder = testRepo.amendRef("HEAD").insertChangeId(changeId.substring(1));
      commitBuilder = testRepo.branch("HEAD").commit().insertChangeId(nextChangeId());
    commitBuilder.message(subject).author(i).committer(new PersonIdent(i, testRepo.getDate()));
        AnnotatedTag annotatedTag = (AnnotatedTag) tag;
        tagCommand
            .setAnnotated(true)
            .setMessage(annotatedTag.message)
            .setTagger(annotatedTag.tagger);
    return new Result(ref, pushHead(testRepo, ref, tag != null, force, pushOptions), c, subject);
  public List<String> getPushOptions() {
    return pushOptions;
  }

  public void setPushOptions(List<String> pushOptions) {
    this.pushOptions = pushOptions;
  }

    private Result(String ref, PushResult resSubj, RevCommit commit, String subject) {
      return Iterables.getOnlyElement(queryProvider.get().byKeyPrefix(changeId));
    public void assertPushOptions(List<String> pushOptions) {
      assertEquals(pushOptions, getPushOptions());
    }

    public void assertChange(
        Change.Status expectedStatus, String expectedTopic, TestAccount... expectedReviewers)
        throws OrmException {
    private void assertReviewers(Change c, TestAccount... expectedReviewers) throws OrmException {
      Iterable<Account.Id> actualIds =
          approvalsUtil.getReviewers(db, notesFactory.createChecked(db, c)).all();
      assertThat(actualIds)
          .containsExactlyElementsIn(Sets.newHashSet(TestAccount.ids(expectedReviewers)));
          .named(message(refUpdate))
          .isEqualTo(Status.REJECTED_OTHER_REASON);
      assertThat(refUpdate.getStatus()).named(message(refUpdate)).isEqualTo(expectedStatus);
      assertThat(message(refUpdate).toLowerCase()).contains(expectedMessage.toLowerCase());