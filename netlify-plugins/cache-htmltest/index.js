module.exports = {
  // Restore htmltest state cached in previous builds.
  // Does not do anything if:
  //  - the file/directory already exists locally
  //  - the file/directory has not been cached yet
  async onPreBuild({ utils }) {
    const success = await utils.cache.restore('./tmp/.htmltest');
    if (success) {
      console.log('Restored the cached htmltest state.');
    } else {
      console.log('No cache found for the htmltest state.');
    }
  },
  // Cache htmltest state for future builds.
  // Does not do anything if:
  //  - the file/directory does not exist locally
  async onPostBuild({ utils }) {
    const success = await utils.cache.save('./tmp/.htmltest');
    if (success) {
      console.log('Cached the htmltest state.');
    } else {
      console.log('No htmltest sate found to cache.');
    }
  }
}
