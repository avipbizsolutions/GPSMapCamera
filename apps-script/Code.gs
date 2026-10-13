/**
 * AVIP GPSCam – registration receiver.
 * Paste into Extensions > Apps Script of the "AVIP GPSCam Registrations" sheet,
 * then Deploy > New deployment > Web app (Execute as: Me, Who has access: Anyone).
 */
const SHEET_ID = '1ASMEmR6BvFZc7YYb9HjGwMDKP-X6ZlSsJ-WYlgC0imI';
const SHEET_NAME = 'Registrations';
const SHARED_KEY = 'avip-gpscam-reg-7Q2x'; // must match Registration.kt

function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    const d = JSON.parse(e.postData.contents);
    if (d.key !== SHARED_KEY) return reply({ ok: false, error: 'unauthorised' });

    lock.waitLock(10000);
    const sheet = SpreadsheetApp.openById(SHEET_ID).getSheetByName(SHEET_NAME);
    const row = [
      new Date(),
      clean(d.name),
      clean(d.company),
      clean(d.mobile),
      d.consent ? 'Yes' : 'No',
      clean(d.device),
      clean(d.android),
      clean(d.appVersion),
      clean(d.installId),
    ];

    // One row per device: a re-registration updates its existing row.
    const last = sheet.getLastRow();
    const ids = last > 1 ? sheet.getRange(2, 9, last - 1, 1).getValues().map(r => r[0]) : [];
    const index = ids.indexOf(row[8]);
    if (index >= 0) {
      sheet.getRange(index + 2, 1, 1, row.length).setValues([row]);
    } else {
      sheet.appendRow(row);
    }
    return reply({ ok: true });
  } catch (err) {
    return reply({ ok: false, error: String(err) });
  } finally {
    lock.releaseLock();
  }
}

// Trim, cap length, and stop text being read as a formula.
function clean(value) {
  const v = String(value == null ? '' : value).trim().slice(0, 100);
  return /^[=+\-@]/.test(v) ? "'" + v : v;
}

function reply(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
