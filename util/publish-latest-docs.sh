#!/bin/bash

set -eu

bash $(dirname $0)/generate-latest-docs.sh

echo -e "Publishing javadoc...\n"
mkdir -p $HOME/guice-docs/latest
cp -R build/docs/* $HOME/guice-docs/latest/

cd $HOME
git config --global user.email "github-actions[bot]@users.noreply.github.com"
git config --global user.name "github-actions[bot]"
# GITHUB_REPOSITORY (owner/name) is set by GitHub Actions to the repository the workflow runs in.
git clone --quiet --branch=gh-pages https://${GH_TOKEN}@github.com/${GITHUB_REPOSITORY} gh-pages > /dev/null

cd gh-pages
if [[ -d api-docs/latest ]]; then
    git rm -rf api-docs/latest
fi
mkdir -p api-docs/latest
cp -rf $HOME/guice-docs/latest/* api-docs/latest/
git add -f .
git commit -m "Latest javadoc on successful CI build $GITHUB_SHA auto-pushed to gh-pages"
git push -fq origin gh-pages > /dev/null

echo -e "Published Javadoc to gh-pages.\n"
