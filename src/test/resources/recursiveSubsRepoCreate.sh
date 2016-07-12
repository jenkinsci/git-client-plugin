#!/bin/bash -ex

rm -rf recursiveSubsRepo recursiveSubsRepo.zip

set -e

mkdir recursiveSubsRepo
pushd recursiveSubsRepo


for repo in repository submodule subsubmodule1 subsubmodule2; do
mkdir $repo
pushd $repo
git init
touch i_am_$repo
git add -A
git commit -am "Initial empty commit in $repo"
popd
done

pushd submodule
git checkout -b use_subsubmodule1
git submodule add ../subsubmodule1 subsubmodule
git commit -a -m 'Use submodule sub1'

git checkout master
git checkout -b use_subsubmodule2
git submodule add --force ../subsubmodule2 subsubmodule
git submodule sync --recursive
pushd subsubmodule
git fetch
git checkout origin/master
popd
git commit -a -m 'Use submodule sub2'
popd

pushd repository
git checkout -b use_subsubmodule1
git submodule add -b use_subsubmodule1 ../submodule submodule
git commit -a -m 'Use submodule with submodule submodule 1'

git checkout -b use_subsubmodule2
pushd submodule
git checkout use_subsubmodule2
git submodule sync
git submodule update --init --recursive
popd
git commit --amend -a -m 'Use submodule with submodule submodule 2'
popd

popd
zip -r recursiveSubsRepo.zip recursiveSubsRepo
rm -rf recursiveSubsRepo
