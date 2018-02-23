Contributing to the Git Client Plugin
=====================================

Plugin source code is hosted on [GitHub](https://github.com/jenkinsci/git-client-plugin).
New feature proposals and bug fix proposals should be submitted as
[GitHub pull requests](https://help.github.com/articles/creating-a-pull-request)
or can be submitted directly if you have commit permission to the
git-client-plugin repository.

Pull requests are evaluated by the
[Cloudbees Jenkins job](https://jenkins.ci.cloudbees.com/job/plugins/job/git-client-plugin/).
You should receive e-mail with the results of the evaluation.

Before submitting your change, please assure that you've added tests
which verify your change.  There have been many developers involved
in the git client plugin and there are many, many users who depend on
the git-client-plugin.  Tests help us assure that we're delivering a
reliable plugin, and that we've communicated our intent to other
developers in a way that they can detect when they run tests.

Code coverage reporting is available as a maven target and is actively
monitored.  Please improve code coverage with tests
when you submit a pull request.

Before submitting your change, please review the findbugs output to
assure that you haven't introduced new findbugs warnings.

# Code Style Guidelines

## Indentation

* Code formatting in the git client plugin varies between files.  Recent additions have generally used the Netbeans "Format" right-click action to maintain consistency.  Try to maintain reasonable consistency with the existing files.
* Please don't perform wholesale reformatting of a file without discussing with the current maintainers.

## Maven POM file layout

* The `pom.xml` file shall use the sequencing of elements as defined by the `mvn tidy:pom` command (after any indenting fix-up).
* All `<plugin>` entries shall have an explicit version defined unless inherited from the parent.
