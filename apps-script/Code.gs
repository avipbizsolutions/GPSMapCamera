/**
 * SolarSite Pro (formerly AVIP GPSCam) – registration receiver.
 * Paste into Extensions > Apps Script of the "AVIP GPSCam Registrations" sheet.
 * To update: Deploy > Manage deployments > edit (pencil) > Version: New version > Deploy.
 * (Editing the existing deployment keeps the same web app URL.)
 *
 * Columns: A Registered at, B Name, C Company, D Mobile, E Marketing consent,
 *          F Device, G Android version, H App version (current), I Install ID,
 *          J App updated at.
 */
const SHEET_ID = '1ASMEmR6BvFZc7YYb9HjGwMDKP-X6ZlSsJ-WYlgC0imI';
const SHEET_NAME = 'Registrations';
const SHARED_KEY = 'avip-gpscam-reg-7Q2x'; // new registration – must match Registration.kt
const UPDATE_KEY = 'avip-gpscam-ver-5Kp9'; // app updated – must match Registration.kt

function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    const d = JSON.parse(e.postData.contents);
    if (d.key !== SHARED_KEY && d.key !== UPDATE_KEY) return reply({ ok: false, error: 'unauthorised' });

    lock.waitLock(10000);
    const sheet = SpreadsheetApp.openById(SHEET_ID).getSheetByName(SHEET_NAME);
    if (!sheet.getRange(1, 10).getValue()) sheet.getRange(1, 10).setValue('App updated at');

    const installId = clean(d.installId);
    const last = sheet.getLastRow();
    const ids = last > 1 ? sheet.getRange(2, 9, last - 1, 1).getValues().map(r => String(r[0])) : [];
    const index = installId ? ids.indexOf(installId) : -1;
    const rowNo = index + 2;

    if (d.key === UPDATE_KEY && index >= 0) {
      // App updated on a registered phone: change only Android version, App version and the time.
      sheet.getRange(rowNo, 7, 1, 2).setValues([[clean(d.android), clean(d.appVersion)]]);
      sheet.getRange(rowNo, 10).setValue(new Date());
      return reply({ ok: true });
    }

    const row = [
      new Date(),
      clean(d.name),
      clean(d.company),
      clean(d.mobile),
      d.consent ? 'Yes' : 'No',
      clean(d.device),
      clean(d.android),
      clean(d.appVersion),
      installId,
    ];
    if (index >= 0) {
      // Re-registration of the same phone: update its row, keep the first registration time.
      row[0] = sheet.getRange(rowNo, 1).getValue() || row[0];
      sheet.getRange(rowNo, 1, 1, row.length).setValues([row]);
      sheet.getRange(rowNo, 10).setValue(new Date());
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
