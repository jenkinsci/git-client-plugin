# Git Client Plugin

[![Build Status](https://ci.jenkins.io/job/Plugins/job/git-client-plugin/job/master/badge/icon)](https://ci.jenkins.io/job/Plugins/job/git-client-plugin/job/master/)
[![Contributors](https://img.shields.io/github/contributors/jenkinsci/git-client-plugin.svg)](https://github.com/jenkinsci/git-client-plugin/graphs/contributors)
[![GitHub release](https://img.shields.io/github/release/jenkinsci/git-client-plugin.svg?label=release)](https://github.com/jenkinsci/git-client-plugin/releases/latest)

<img src="https://git-scm.com/images/logos/downloads/Git-Logo-2Color.png" width="303">

## Introduction

The git client plugin provides git application programming interfaces (APIs) for Jenkins plugins.
It can fetch, checkout, branch, list, merge, and tag repositories.
Refer to the [API documentation](https://javadoc.jenkins-ci.org/plugin/git-client/) for specific API details.

The [GitClient interface](https://javadoc.jenkins-ci.org/plugin/git-client/org/jenkinsci/plugins/gitclient/GitClient.html) provides the primary entry points for git access.
It support username / password credentials and private key credentials using the [Jenkins credentials plugin](https://plugins.jenkins.io/credentials).

## Changelog

Release notes are recorded in [GitHub](https://github.com/jenkinsci/git-client-plugin/releases) beginning with git client plugin 2.8.1.
Prior release notes are recorded on the [Jenkins wiki](https://wiki.jenkins.io/display/JENKINS/Git+Client+Plugin#GitClientPlugin-ChangeLog-MovedtoGitHub).

## Implementations

The git client plugin default implementation requires that [command line git](https://git-scm.com/downloads) is installed on the master and on every agent that will use git.
Command line git implementations working with large files should also install [git LFS](https://git-lfs.github.com/).
The command line git implementation is the canonical implementation of the git interfaces provided by the git client plugin.

Command line git is **enabled by default** when the git client plugin is installed.

### JGit

The git client plugin also includes two optional implementations that use [Eclipse JGit](https://www.eclipse.org/jgit/).
Eclipse JGit is a pure Java implementation of git.
The JGit implementation in the git client plugin provides most of the functionality of the command line git implementation.
When the JGit implementation is incomplete, the gap is noted in console logs.

JGit is **disabled by default** when the git client plugin is installed.

### Enabling JGit

Click the "**Add Git**" button in the "**Global Tool Configuration**" section under "**Manage Jenkins**" to add JGit or JGit with Apache HTTP Client as a git implementation.

![Enable JGit or JGit with Apache HTTP Client](images/enable-jgit.png)

### JGit with Apache HTTP Client

The original JGit implementation inside the git client plugin had issues with active directory authentication.
A workaround was implemented to provide JGit but use Apache HTTP client for authentication.
The issue in JGit has now been resolved and delivered in git client plugin releases.
JGit with Apache HTTP Client continues to delivered to assure compatibility.

## Windows Credentials Manager

Git for Windows is able to integrate with the Windows Credentials Manager for secure storage of credentials.
Windows Credentials Manager works very well for interactive users on the Windows desktop.
Windows Credentials Manager does not work as well for batch processing in the git client plugin.
It is best to disable Windows Credentials Manager when installing Git on Jenkins agents running Windows.

## Bug Reports

Report issues and enhancements with the [Jenkins issue tracker](https://issues.jenkins-ci.org).

## Contributing to the Plugin

Refer to [contributing to the plugin](CONTRIBUTING.md) for contribution guidelines.
