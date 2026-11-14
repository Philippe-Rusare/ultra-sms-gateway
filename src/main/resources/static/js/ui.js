/*
 * Behaviour shared by the dashboard pages, with no dependency:
 *  - [data-dialog="id"] opens <dialog id="id">. On the trigger, data-action sets the action of the dialog's
 *    form and data-set-<field> fills its field named <field> (e.g. data-set-name).
 *  - [data-dialog-close] closes its dialog; a click on the backdrop or Escape too.
 *  - <form data-confirm="text"> asks for confirmation in a dialog before it is sent, with data-confirm-title,
 *    data-confirm-ok and data-confirm-cancel for the title and the buttons.
 *  - [data-modal-remote] links open their page in a dialog (the server answers the X-Modal header with a
 *    fragment); forms inside it are sent in the background, and an answer holding [data-reload] reloads
 *    the page.
 *  - [role="tablist"] inside [data-tabs] switches the tab panels.
 * Listeners run in the capture phase so that they come before the page's loading placeholder.
 */
(function () {
    'use strict';

    function focusFirst(dialog) {
        var target = dialog.querySelector('[autofocus], input:not([type=hidden]):not([disabled]), select, textarea, button:not([data-dialog-close])');
        if (target) target.focus();
    }

    function openDialog(dialog) {
        if (!dialog.open) dialog.showModal();
        focusFirst(dialog);
    }

    // ---- Dialogs written in the page ----
    document.addEventListener('click', function (event) {
        var trigger = event.target.closest('[data-dialog]');
        if (trigger) {
            var dialog = document.getElementById(trigger.dataset.dialog);
            if (!dialog) return;
            event.preventDefault();
            var form = dialog.querySelector('form');
            if (form && trigger.dataset.action) form.action = trigger.dataset.action;
            Object.keys(trigger.dataset).forEach(function (key) {
                if (key.indexOf('set') !== 0 || key.length < 4) return;
                var name = key.charAt(3).toLowerCase() + key.slice(4);
                var field = dialog.querySelector('[name="' + name + '"]');
                if (field) field.value = trigger.dataset[key];
            });
            openDialog(dialog);
            return;
        }
        var close = event.target.closest('[data-dialog-close]');
        if (close) {
            event.preventDefault();
            close.closest('dialog').close();
            return;
        }
        // A click on the backdrop: the dialog element itself is the target, outside its box.
        if (event.target.tagName === 'DIALOG' && event.target.classList.contains('modal')) {
            event.target.close();
        }
    }, true);

    // ---- Confirmation before a destructive action ----
    var confirmDialog = null;
    function askConfirmation(form) {
        if (!confirmDialog) {
            confirmDialog = document.createElement('dialog');
            confirmDialog.className = 'modal modal-small';
            confirmDialog.setAttribute('aria-labelledby', 'confirm-title');
            confirmDialog.innerHTML = '<div class="modal-box"><div class="modal-head"><h2 id="confirm-title"></h2></div>'
                + '<div class="modal-body"><p class="confirm-text"></p></div>'
                + '<div class="modal-actions"><button type="button" class="secondary" data-dialog-close></button>'
                + '<button type="button" class="danger-solid confirm-ok"></button></div></div>';
            document.body.appendChild(confirmDialog);
        }
        confirmDialog.querySelector('#confirm-title').textContent = form.dataset.confirmTitle || '';
        confirmDialog.querySelector('.confirm-text').textContent = form.dataset.confirm;
        confirmDialog.querySelector('[data-dialog-close]').textContent = form.dataset.confirmCancel || 'Cancel';
        var ok = confirmDialog.querySelector('.confirm-ok');
        ok.textContent = form.dataset.confirmOk || 'OK';
        ok.onclick = function () {
            confirmDialog.close();
            form.dataset.confirmed = 'true';
            if (form.requestSubmit) form.requestSubmit(); else form.submit();
        };
        confirmDialog.showModal();
        ok.focus();
    }

    // ---- Pages opened in a dialog ----
    var remote = null;
    function remoteDialog() {
        if (!remote) {
            remote = document.createElement('dialog');
            remote.className = 'modal modal-wide';
            remote.innerHTML = '<div class="modal-box"></div>';
            document.body.appendChild(remote);
        }
        return remote;
    }
    function showRemote(html) {
        var dialog = remoteDialog();
        var box = dialog.querySelector('.modal-box');
        var tab = box.querySelector('[role=tab][aria-selected=true]');
        box.innerHTML = html;
        if (box.querySelector('[data-reload]')) {
            window.location.reload();
            return;
        }
        if (tab) selectTab(box.querySelector('[role=tab][aria-controls="' + tab.getAttribute('aria-controls') + '"]'));
        openDialog(dialog);
    }
    function load(url, options) {
        options = options || {};
        options.headers = {'X-Modal': '1'};
        options.credentials = 'same-origin';
        document.body.classList.add('is-busy');
        return fetch(url, options).then(function (response) {
            if (!response.ok || response.headers.get('X-Modal') !== '1') throw new Error('Not a dialog page');
            return response.text();
        }).then(showRemote).catch(function () {
            window.location = url;
        }).finally(function () {
            document.body.classList.remove('is-busy');
        });
    }
    document.addEventListener('click', function (event) {
        var link = event.target.closest('a[data-modal-remote]');
        if (!link || event.metaKey || event.ctrlKey || event.shiftKey || event.button !== 0) return;
        event.preventDefault();
        event.stopPropagation();
        load(link.href);
    }, true);

    document.addEventListener('submit', function (event) {
        var form = event.target;
        if (form.dataset.confirm && !form.dataset.confirmed) {
            event.preventDefault();
            event.stopPropagation();
            askConfirmation(form);
            return;
        }
        if (remote && remote.contains(form)) {
            event.preventDefault();
            event.stopPropagation();
            load(form.action, {method: 'POST', body: new FormData(form)});
        }
    }, true);

    // ---- Tabs ----
    function selectTab(tab) {
        if (!tab) return;
        var list = tab.closest('[role=tablist]');
        list.querySelectorAll('[role=tab]').forEach(function (other) {
            var selected = other === tab;
            other.setAttribute('aria-selected', String(selected));
            other.tabIndex = selected ? 0 : -1;
            var panel = document.getElementById(other.getAttribute('aria-controls'));
            if (panel) panel.hidden = !selected;
        });
    }
    document.addEventListener('click', function (event) {
        var tab = event.target.closest('[data-tabs] [role=tab]');
        if (tab) {
            event.preventDefault();
            selectTab(tab);
        }
    });
    document.addEventListener('keydown', function (event) {
        var tab = event.target.closest && event.target.closest('[data-tabs] [role=tab]');
        if (!tab || (event.key !== 'ArrowDown' && event.key !== 'ArrowUp' && event.key !== 'ArrowRight' && event.key !== 'ArrowLeft')) return;
        var tabs = Array.prototype.slice.call(tab.closest('[role=tablist]').querySelectorAll('[role=tab]'));
        var step = event.key === 'ArrowDown' || event.key === 'ArrowRight' ? 1 : -1;
        var next = tabs[(tabs.indexOf(tab) + step + tabs.length) % tabs.length];
        event.preventDefault();
        selectTab(next);
        next.focus();
    });

    // A dialog asked to open with the page, e.g. to show the error of the form it holds.
    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('dialog[data-open]').forEach(openDialog);
    });
})();
