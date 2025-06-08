function errorModal(body, title = 'Error') {
  document.getElementById('error-modal-title').innerHTML = title;
  document.getElementById('error-modal-body').innerHTML = body;
  openModal(document.getElementById('error-modal'));
}

function checkActionResponse(req, url) {
  if (req.status > 400) {
    errorModal("Problem requesting " + url + "<br><br>" +
               "Server responded: " + req.status + ", " + req.statusText, "Request Failed");
  } else {
    console.log(req.responseText);
    result = JSON.parse(req.responseText);
    if (result.error) {
      errorModal(result.error.details, result.error.title);
    }
  }
}

function runAction(url) {
  const req = new XMLHttpRequest();
  req.open("GET", url);
  req.onerror = () => { errorModal("There was a network problem requesting " + url + "<br><br>" +
                                   "You might want to check that Beat Link Trigger is running?", "Request Failed") };
  req.onload = () => { checkActionResponse(req, url); };
  req.send();
}

function showParam() {
  return showFile? ("show=" + showFile + "&") : "";
}

function editShowExpression(kind) {
  runAction("/show/edit-show-expression?" + showParam() + (kind? ("kind=" + kind) : ""));
}

function simulateTrackExpression(signature, kind) {
  runAction("/show/simulate-track-expression?" + showParam() + "track=" + signature + "&kind=" + kind);
}

function editTrackExpression(signature, kind) {
  runAction("/show/edit-track-expression?" + showParam() + "track=" + signature + (kind? ("&kind=" + kind) : ""));
}

function simulateTrackCueExpression(signature, cue, kind) {
  runAction("/show/simulate-track-cue-expression?" + showParam() + "track=" + signature +
            "&cue=" + cue + "&kind=" + kind);
}

function editTrackCueExpression(signature, cue, kind) {
  runAction("/show/edit-track-cue-expression?" + showParam() + "track=" + signature +
            "&cue=" + cue + (kind? ("&kind=" + kind) : ""));
}

function simulatePhraseExpression(uuid, kind) {
  runAction("/show/simulate-phrase-expression?" + showParam() + "phrase=" + uuid + "&kind=" + kind);
}

function editPhraseExpression(uuid, kind) {
  runAction("/show/edit-phrase-expression?" + showParam() + "phrase=" + uuid + (kind? ("&kind=" + kind) : ""));
}

function simulatePhraseCueExpression(uuid, cue, kind) {
  runAction("/show/simulate-phrase-cue-expression?" + showParam() + "phrase=" + uuid +
            "&cue=" + cue + "&kind=" + kind);
}

function editPhraseCueExpression(uuid, cue, kind) {
  runAction("/show/edit-phrase-cue-expression?" + showParam() + "phrase=" + uuid +
            "&cue=" + cue + (kind? ("&kind=" + kind) : ""));
}

function simulateTriggerExpression(uuid, kind) {
  runAction("/show/simulate-trigger-expression?uuid=" + uuid + "&kind=" + kind);
 }

function editTriggerExpression(uuid, kind) {
  runAction("/show/edit-trigger-expression?uuid=" + uuid + (kind? ("&kind=" + kind) : ""));
 }


// Functions supporting Bulma modals.

  // Functions to open and close a modal
  function openModal($el) {
    $el.classList.add('is-active');
  }

  function closeModal($el) {
    $el.classList.remove('is-active');
  }

  function closeAllModals() {
    (document.querySelectorAll('.modal') || []).forEach(($modal) => {
      closeModal($modal);
    });
  }

document.addEventListener('DOMContentLoaded', () => {
  // Add a click event on buttons to open a specific modal
  (document.querySelectorAll('.js-modal-trigger') || []).forEach(($trigger) => {
    const modal = $trigger.dataset.target;
    const $target = document.getElementById(modal);

    $trigger.addEventListener('click', () => {
      openModal($target);
    });
  });

  // Add a click event on various child elements to close the parent modal
  (document.querySelectorAll('.modal-background, .modal-close, .modal-card-head .delete, .modal-card-foot .button') || []).forEach(($close) => {
    const $target = $close.closest('.modal');

    $close.addEventListener('click', () => {
      closeModal($target);
    });
  });

  // Add a keyboard event to close all modals
  document.addEventListener('keydown', (event) => {
    const e = event || window.event;

    if (e.keyCode === 27) { // Escape key
      closeAllModals();
    }
  });
});
