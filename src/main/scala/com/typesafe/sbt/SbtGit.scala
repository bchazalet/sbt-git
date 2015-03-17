package com.typesafe.sbt

import sbt._
import Keys._
import git.{ ConsoleGitRunner, GitRunner, JGitRunner, NullLogger }
import scala.util.logging.ConsoleLogger
import com.typesafe.sbt.git.GitRunner
import com.typesafe.sbt.git.ReadableGit
import com.typesafe.sbt.git.DefaultReadableGit

/** This plugin has all the basic 'git' functionality for other plugins. */
object SbtGit extends AutoPlugin {

  override def requires = sbt.plugins.CorePlugin
  override def trigger = allRequirements

  object GitKeys {
    // Read-only git settings and values for use in other build settings.
    // Note: These are all grabbed using jgit currently.
    val gitReader = SettingKey[ReadableGit]("git-reader", "This gives us a read-only view of the git repository.")
    val gitBranch = SettingKey[Option[String]]("git-branch", "Target branch of a git operation")
    val gitBranches = SettingKey[Seq[String]]("git-branches", "All branches found in this repository")
    val gitCurrentBranch = SettingKey[String]("git-current-branch", "The current branch for this project.")
    val gitCurrentTags = SettingKey[Seq[String]]("git-current-tags", "The tags associated with this commit.")
    val gitHeadCommit = SettingKey[Option[String]]("git-head-commit", "The commit sha for the top commit of this project.")
    val gitDescribedVersion = SettingKey[Option[String]]("git-described-version", "Version as returned by `git describe --tags`.")
    val gitUncommittedChanges = SettingKey[Boolean]("git-uncommitted-changes", "Whether there are uncommitted changes.")
    
    // A Mechanism to run Git directly.
    val gitRunner = TaskKey[GitRunner]("git-runner", "The mechanism used to run git in the current build.")

    // Keys associated with setting a version number.
    val useGitDescribe = SettingKey[Boolean]("use-git-describe", "Get version by calling `git describe` on the repository")
    val gitTagToVersionNumber = SettingKey[String => Option[String]]("git-tag-to-version-number", "Converts a git tag string to a version number.")
    val baseVersion = SettingKey[String]("base-version", "The base version number which we will append the git version to.  May be left unspecified.")
    val versionProperty = SettingKey[String]("version-property", "The system property that can be used to override the version number.  Defaults to `project.version`.")
    val uncommittedSignifier = SettingKey[Option[String]]("uncommitted-signifier", "Optional additional signifier to signify uncommitted changes")
    
    // The remote repository we're using.
    val gitRemoteRepo = SettingKey[String]("git-remote-repo", "The remote git repository associated with this project")
  }

  object GitCommand {
    val action: (State, Seq[String]) => State = { (state, args) =>
      val extracted = Project.extract(state)
      import extracted._
      val (state2, runner) = extracted.runTask(GitKeys.gitRunner, state)
      val dir = extracted.get(baseDirectory)
      val result = runner(args:_*)(dir, state2.log)
      state2
    }
    
    val justPrint: (State, Seq[String]) => State = { (state, args) =>
      state
    }

    // <arg> is the suggestion printed for tab completion on an argument
    val command: Command = Command.args("git", "<args>")(action)
    
    import complete._
    import complete.DefaultParsers._
    import sbt._
    
    def fullCommand(state: State) = {
      val extracted = Project.extract(state)
      import extracted._
      val reader = extracted.get(GitKeys.gitReader)
      implicit val branches: Seq[String] = reader.withGit(_.allBranches)
      // token(Space ~> gitCommand <~ Space) ~ token(revision)
      token(Space ~> branchCompletableCommand) ~ (Space ~> token(revision)).*
    }
    
    // commands for which we should suggest branch names
    val branchCompletable = Set("checkout", "merge")
        
    // let's not forget the user can define its own git commands and aliases so we don't really parse the command, we only provide suggestions
    // we parse the command, whatever it is until we encounter a space
    val branchCompletableCommand: Parser[Seq[Char]] = charClass(!_.isSpaceChar, "git main command").+.examples(branchCompletable.toSet)
    
    def revision(implicit branches: Seq[String]): Parser[Seq[Char]] = charClass(!_.isSpaceChar, "git revision").+.examples(branches.toSet)
    
    // how do I make the parser depend on the state in order to get access to the git reader?
    def commandGitTest: Command = Command("git-test")(s =>  fullCommand(s)){ (state, arg) =>
      val (command, args) = arg
      val all = command.mkString +: args.map(_.mkString)
      action(state, all)
    }
    
    private def isGitRepo(dir: File): Boolean = {
      if (System.getenv("GIT_DIR") != null) true
      else isGitDir(dir)
    }

    @scala.annotation.tailrec
    private def isGitDir(dir: File): Boolean = {
      if (dir.listFiles().map(_.getName).contains(".git")) true
      else {
        val parent = dir.getParentFile
        if (parent == null) false
        else isGitDir(parent)
      }
    }

    val prompt: State => String = { state =>
      val extracted = Project.extract(state)
      import extracted._
      val reader = extracted get GitKeys.gitReader
      val dir = extracted get baseDirectory
      val name = extracted get Keys.name
      if (isGitRepo(dir)) {
        val branch = reader.withGit(_.branch)
        name + "(" + branch + ")> "
      } else {
        name + "> "
      }
    }
  }

  // Build settings.
  import GitKeys._
  override def buildSettings = Seq(
    gitReader := new DefaultReadableGit(baseDirectory.value),
    gitRunner := ConsoleGitRunner,
    gitHeadCommit := gitReader.value.withGit(_.headCommitSha),
    gitDescribedVersion := gitReader.value.withGit(_.describedVersion),
    gitCurrentTags := gitReader.value.withGit(_.currentTags),
    gitCurrentBranch := Option(gitReader.value.withGit(_.branch)).getOrElse(""),
    gitUncommittedChanges in ThisBuild := gitReader.value.withGit(_.hasUncommittedChanges)
  )
  override val projectSettings = Seq(
    // Input task to run git commands directly.
    commands ++= Seq(GitCommand.command, GitCommand.commandGitTest)
  )

  /** A Predefined setting to use JGit runner for git. */
  def useJGit: Setting[_] = gitRunner in ThisBuild := JGitRunner

  /** Adapts the project prompt to show the current project name *and* the current git branch. */
  def showCurrentGitBranch: Setting[_] =
    shellPrompt := GitCommand.prompt


  /** Uses git to control versioning.
   *
   * Versioning runs through the following:
   *
   * 1. Looks at version-property settings, and checks the sys.props to see if this has a value.
   * 2. Looks at the project tags.  The first to match the `gitTagToVersionNumberSetting` is used to assign the version.
   * 3. if we have a head commit, we attach this to the base version setting "<base-version>.<git commit sha>"
   * 4. We append the current timestamp tot he base version: "<base-version>.<timestamp>"
   */
  def versionWithGit: Seq[Setting[_]] =
    Seq(
        gitTagToVersionNumber in ThisBuild := (git.defaultTagByVersionStrategy _),
        versionProperty in ThisBuild := "project.version",
        uncommittedSignifier in ThisBuild := Some("SNAPSHOT"),
        useGitDescribe in ThisBuild := false,
        version in ThisBuild := {
          git.makeVersion(
            versionProperty=git.versionProperty.value,
            baseVersion = git.baseVersion.?.value,
            headCommit = git.gitHeadCommit.value,
            useGitDescribe = git.useGitDescribe.value,
            gitDescribedVersion = git.gitDescribedVersion.value,
            currentTags = git.gitCurrentTags.value,
            releaseTagVersion = git.gitTagToVersionNumber.value,
            hasUncommittedChanges = git.gitUncommittedChanges.value, 
            uncommittedSignifier = git.uncommittedSignifier.value
          )
        }
    )

  /** A holder of keys for simple config. */
  object git {
    val remoteRepo = GitKeys.gitRemoteRepo
    val branch = GitKeys.gitBranch
    val runner = GitKeys.gitRunner in ThisBuild
    val gitHeadCommit = GitKeys.gitHeadCommit in ThisBuild
    val useGitDescribe = GitKeys.useGitDescribe in ThisBuild
    val gitDescribedVersion = GitKeys.gitDescribedVersion in ThisBuild
    val gitCurrentTags = GitKeys.gitCurrentTags in ThisBuild
    val gitCurrentBranch = GitKeys.gitCurrentBranch in ThisBuild
    val gitTagToVersionNumber = GitKeys.gitTagToVersionNumber in ThisBuild
    val baseVersion = GitKeys.baseVersion in ThisBuild
    val versionProperty = GitKeys.versionProperty in ThisBuild
    val gitUncommittedChanges = GitKeys.gitUncommittedChanges in ThisBuild
    val uncommittedSignifier = GitKeys.uncommittedSignifier in ThisBuild

    def defaultTagByVersionStrategy(tag: String): Option[String] = {
      if(tag matches "v[0-9].*") Some(tag drop 1)
      else None
    }
    // Simple fall-through on how to define the project version.
    // TODO - Split this to use multiple settings, perhaps.
    def makeVersion(versionProperty: String, baseVersion: Option[String], headCommit: Option[String], useGitDescribe:Boolean, gitDescribedVersion:Option[String], currentTags: Seq[String], releaseTagVersion: String => Option[String], hasUncommittedChanges: Boolean, uncommittedSignifier: Option[String]): String = {
      // The version string passed in via command line settings, if desired.
      def overrideVersion = Option(sys.props(versionProperty))
      // Version string that is computed from tags.
      def releaseVersion: Option[String] = {
        val releaseVersions =
          for {
            tag <- currentTags
            version <- releaseTagVersion(tag)
          } yield version
        // NOTE - Selecting the last tag or the first tag should be an option.
        releaseVersions.reverse.headOption
      }
      def describedVersion: Option[String] = if(useGitDescribe) gitDescribedVersion else None

      val basePrefix = baseVersion.map(_ + "-").getOrElse("")
      val signifierSuffix = uncommittedSignifier.map("-" + _).filter(_ => hasUncommittedChanges).getOrElse("")
      
      // Version string that just uses the commit version.
      def commitVersion: Option[String] =
         headCommit map (sha => basePrefix + sha + signifierSuffix)
      // Version string that just uses the full timestamp.
      def datedVersion: String = {
        val df = new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss")
        df setTimeZone java.util.TimeZone.getTimeZone("GMT")
        basePrefix + (df format (new java.util.Date))
      }
      //Now we fall through the potential version numbers...
      overrideVersion  orElse releaseVersion orElse describedVersion orElse commitVersion getOrElse datedVersion
    }
  }
  // Note: In an attempt to pretend we are binary compatible, we current add this as an after thought.
  // In 1.0, we should deprecate/move the other meaans of getting these values.
  object autoImport {
    val git = SbtGit.git
    def versionWithGit = SbtGit.versionWithGit
    def useJGit = SbtGit.useJGit
    def showCurrentGitBranch = SbtGit.showCurrentGitBranch
  }
}

/** Adapter to auto-enable git versioning.  i.e. the sbt 0.13.5+ mechanism of turning it on. */
object GitVersioning extends AutoPlugin {
  override def requires = sbt.plugins.IvyPlugin && SbtGit
  override def projectSettings = SbtGit.autoImport.versionWithGit
}
/** Adapter to enable the git prompt. i.e. rich prompt based on git info. */
object GitBranchPrompt extends AutoPlugin {
  override def requires = SbtGit
  override  def projectSettings = SbtGit.showCurrentGitBranch
}
