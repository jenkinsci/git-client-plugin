Utility plugin for Git-related support
======================================

Extracted from [git-plugin](https://wiki.jenkins-ci.org/display/JENKINS/Git+Plugin)
to make it easier for other plugins to use and contribute new features.

* see [Jenkins wiki](https://wiki.jenkins-ci.org/display/JENKINS/Git+Plugin) for detailed feature descriptions
* use [JIRA](https://issues.jenkins-ci.org) to report issues / feature requests

Contributing to the Plugin
==========================

Plugin source code is hosted on [GitHub](https://github.com/jenkinsci/git-client-plugin).
New feature proposals and bug fix proposals should be submitted as
[GitHub pull requests](https://help.github.com/articles/creating-a-pull-request).
Fork the repository on GitHub, prepare your change on your forked
copy, and submit a pull request.  Your pull request will be evaluated
by the [Cloudbees Jenkins job](https://jenkins.ci.cloudbees.com/job/plugins/job/git-client-plugin/)
and you should receive e-mail with the results of the evaluation.

Before submitting your change, please assure that you've added a test
which verifies your change.  There have been many developers involved
in the git client plugin and there are many, many users who depend on
the git-client-plugin.  Tests help us assure that we're delivering a
reliable plugin, and that we've communicated our intent to other
developers in a way that they can detect when they run tests.

Before submitting your change, please review the findbugs output to
assure that you haven't introduced new findbugs warnings.
