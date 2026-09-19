/*
 * Points one hostname at a fixed address, inside the Node process only.
 *
 * ## Why this is not a hack in the way it looks
 *
 * An Android emulator has no usable name resolution: its `getaddrinfo` goes
 * through `netd`, so `resolv.conf` is ignored, and the system image here is a
 * `user` build, so `/etc/hosts` cannot be written without root. Measured on the
 * guest: TLS to the relay's IP with the right SNI succeeds and `/v1/models`
 * returns 200, while the same request by name fails with `ENOTFOUND`. The network
 * is fine; only the lookup is missing.
 *
 * This module supplies that one lookup from the environment, so the **rest of the
 * path can be exercised for real**: PiKit writes `models.json`, launches pi
 * against the custom provider, pi makes an authenticated streaming request, and
 * the app parses what comes back. Everything except the DNS answer is production
 * code.
 *
 * It is set through `NODE_OPTIONS` for a test run only. PiKit never sets it, and
 * the module refuses to do anything unless `PIKIT_DNS_PINS` is present — so if it
 * ever leaked into a build, it would be inert.
 *
 * ## Format
 *
 * `PIKIT_DNS_PINS=host=address[,host=address]`, e.g.
 * `PIKIT_DNS_PINS=www.example.com=104.18.6.12`
 *
 * Both `lookup` entry points are replaced because undici uses the callback form
 * while some callers use the promise form, and undici also asks for `all`.
 */

'use strict';

const dns = require('node:dns');

const pins = new Map();
for (const pair of String(process.env.PIKIT_DNS_PINS || '').split(',')) {
  const at = pair.indexOf('=');
  if (at <= 0) continue;
  pins.set(pair.slice(0, at).trim(), pair.slice(at + 1).trim());
}

if (pins.size > 0) {
  const originalLookup = dns.lookup.bind(dns);

  const pinned = (hostname, options, callback) => {
    // `options` is optional in both signatures.
    if (typeof options === 'function') {
      callback = options;
      options = {};
    }
    const address = pins.get(hostname);
    if (!address) return originalLookup(hostname, options, callback);

    const family = address.includes(':') ? 6 : 4;
    if (options && options.all) {
      return process.nextTick(() => callback(null, [{ address, family }]));
    }
    // Passing the numeric address as the hostname makes the real resolver see a
    // literal, which it returns without a query.
    return originalLookup(address, options, callback);
  };

  dns.lookup = (hostname, options, callback) => {
    if (typeof options === 'function') {
      callback = options;
      return pinned(hostname, {}, callback);
    }
    return pinned(hostname, options, callback);
  };

  if (dns.promises) {
    const originalPromiseLookup = dns.promises.lookup.bind(dns.promises);
    dns.promises.lookup = async (hostname, options) => {
      const address = pins.get(hostname);
      if (!address) return originalPromiseLookup(hostname, options);
      const family = address.includes(':') ? 6 : 4;
      return options && options.all ? [{ address, family }] : { address, family };
    };
  }

  // Printed so a run that still fails can be told apart from one where the pin
  // never applied.
  process.stderr.write(
    `[pikit-dns-pin] ${pins.size} host(s) pinned: ${[...pins.keys()].join(', ')}\n`,
  );
}
