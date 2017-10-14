Utility plugin for Git-related support
======================================

Extracted from [git-plugin](https://wiki.jenkins-ci.org/display/JENKINS/Git+Plugin)
to make it easier for other plugins to use and contribute new features.
Includes JGit as a library so that other Jenkins components can rely on
JGit whenever the git client plugin is available.

* see [Jenkins wiki](https://wiki.jenkins-ci.org/display/JENKINS/Git+Client+Plugin) for feature descriptions
* use [JIRA](https://issues.jenkins-ci.org) to report issues / feature requests

Contributing to the Plugin
==========================

Refer to [contributing to the plugin](https://github.com/jenkinsci/git-client-plugin/blob/master/CONTRIBUTING.md)
for suggestions to speed the acceptance of your contributions.

Building the Plugin
===================

  $ java -version # Requires Java 1.8
  $ mvn -version # Requires a modern maven version; maven 3.2.5 and 3.5.0 are known to work
  $ mvn clean install

To Do
=====

* Evaluate [pull requests](https://github.com/jenkinsci/git-client-plugin/pulls)
* Fix [bugs](https://issues.jenkins-ci.org/secure/IssueNavigator.jspa?mode=hide&reset=true&jqlQuery=project+%3D+JENKINS+AND+status+in+%28Open%2C+"In+Progress"%2C+Reopened%29+AND+component+%3D+git-client-plugin)
* Create infrastructure to detect [files opened during a unit test](https://issues.jenkins-ci.org/browse/JENKINS-19994) and left open at exit from test
* Complete more of the JGit implementation
