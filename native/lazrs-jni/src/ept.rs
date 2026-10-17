//! EPT (Entwine Point Tile) -> standard LAZ tile rebuild.
//!
//! USGS 3DEP EPT nodes store every original return reprojected to EPSG:3857 at 1 mm. Kotlin
//! downloads the octree nodes that intersect one TNM tile, then [merge] reads each node with the
//! laz decoder, maps x/y back through Web Mercator -> geographic -> Transverse Mercator (NAD83
//! UTM, GRS80, Krüger 6th order, sub-micron), re-quantizes with the original tile's scale/offset,
//! keeps the points inside the original header's quantized bounds and writes a LAZ that carries
//! the original tile's header and CRS VLRs. On the NY test tile this reproduces the rockyweb
//! file's point set exactly (same count, same integer X/Y/Z).

use las::{Read as _, Write as _};
use rayon::prelude::*;
use std::fs::File;
use std::io::{BufReader, BufWriter, Read, Seek, SeekFrom};

const R_MERC: f64 = 6378137.0;

pub struct Tm {
    k0a: f64,
    e: f64,
    alpha: [f64; 6],
    lon0: f64,
    fe: f64,
    fnorth: f64,
}

impl Tm {
    /// GRS80 Transverse Mercator. lon0 in degrees.
    pub fn new(lon0_deg: f64, k0: f64, fe: f64, fnorth: f64) -> Tm {
        let a: f64 = 6378137.0;
        let f: f64 = 1.0 / 298.257222101;
        let n = f / (2.0 - f);
        let (n2, n3, n4, n5, n6) = (n * n, n.powi(3), n.powi(4), n.powi(5), n.powi(6));
        let big_a = a / (1.0 + n) * (1.0 + n2 / 4.0 + n4 / 64.0 + n6 / 256.0);
        let alpha = [
            n / 2.0 - 2.0 / 3.0 * n2 + 5.0 / 16.0 * n3 + 41.0 / 180.0 * n4 - 127.0 / 288.0 * n5 + 7891.0 / 37800.0 * n6,
            13.0 / 48.0 * n2 - 3.0 / 5.0 * n3 + 557.0 / 1440.0 * n4 + 281.0 / 630.0 * n5 - 1983433.0 / 1935360.0 * n6,
            61.0 / 240.0 * n3 - 103.0 / 140.0 * n4 + 15061.0 / 26880.0 * n5 + 167603.0 / 181440.0 * n6,
            49561.0 / 161280.0 * n4 - 179.0 / 168.0 * n5 + 6601661.0 / 7257600.0 * n6,
            34729.0 / 80640.0 * n5 - 3418889.0 / 1995840.0 * n6,
            212378941.0 / 319334400.0 * n6,
        ];
        Tm { k0a: k0 * big_a, e: (f * (2.0 - f)).sqrt(), alpha, lon0: lon0_deg.to_radians(), fe, fnorth }
    }

    pub fn forward(&self, lat: f64, lon: f64) -> (f64, f64) {
        let l = lon - self.lon0;
        let s = lat.sin();
        let t = (s.atanh() - self.e * (self.e * s).atanh()).sinh();
        let xi = t.atan2(l.cos());
        let eta = (l.sin() / (1.0 + t * t).sqrt()).atanh();
        let (mut x, mut y) = (eta, xi);
        for (j, a) in self.alpha.iter().enumerate() {
            let k = 2.0 * (j as f64 + 1.0);
            x += a * (k * xi).cos() * (k * eta).sinh();
            y += a * (k * xi).sin() * (k * eta).cosh();
        }
        (self.fe + self.k0a * x, self.fnorth + self.k0a * y)
    }

    /// EPSG:3857 metres -> projected TM metres.
    pub fn from_merc(&self, mx: f64, my: f64) -> (f64, f64) {
        let lon = mx / R_MERC;
        let lat = (my / R_MERC).sinh().atan();
        self.forward(lat, lon)
    }
}

pub struct MergeParams {
    pub template: String,
    pub inputs: Vec<String>,
    pub out: String,
    pub tm: Tm,
}

pub struct MergeStats {
    pub read: u64,
    pub kept: u64,
}

fn read_template(path: &str) -> Result<(las::Builder, [i64; 6]), String> {
    let mut f = BufReader::new(File::open(path).map_err(|e| e.to_string())?);
    let raw = las::raw::Header::read_from(&mut f).map_err(|e| e.to_string())?;
    f.seek(SeekFrom::Start(raw.header_size as u64)).map_err(|e| e.to_string())?;
    let mut vlrs = Vec::new();
    for _ in 0..raw.number_of_variable_length_records {
        let v = las::raw::Vlr::read_from(&mut f, false).map_err(|e| e.to_string())?;
        let v = las::Vlr::new(v);
        if v.user_id != "laszip encoded" {
            vlrs.push(v);
        }
    }
    let q = |v: f64, o: f64, s: f64| ((v - o) / s).round() as i64;
    let b = [
        q(raw.min_x, raw.x_offset, raw.x_scale_factor),
        q(raw.max_x, raw.x_offset, raw.x_scale_factor),
        q(raw.min_y, raw.y_offset, raw.y_scale_factor),
        q(raw.max_y, raw.y_offset, raw.y_scale_factor),
        q(raw.min_z, raw.z_offset, raw.z_scale_factor),
        q(raw.max_z, raw.z_offset, raw.z_scale_factor),
    ];
    let mut builder = las::Builder::new(raw).map_err(|e| e.to_string())?;
    builder.vlrs = vlrs;
    builder.evlrs.clear();
    builder.point_format.is_compressed = true;
    builder.point_format.extra_bytes = 0;
    builder.generating_software = "Find It Grok EPT".into();
    Ok((builder, b))
}

pub fn merge(p: &MergeParams) -> Result<MergeStats, String> {
    let (builder, b) = read_template(&p.template)?;
    let header = builder.into_header().map_err(|e| e.to_string())?;
    let tr = *header.transforms();
    let fmt = *header.point_format();
    let out = BufWriter::with_capacity(1 << 20, File::create(&p.out).map_err(|e| e.to_string())?);
    let mut writer = las::Writer::new(out, header).map_err(|e| e.to_string())?;
    let mut stats = MergeStats { read: 0, kept: 0 };
    for batch in p.inputs.chunks(64) {
        let decoded: Vec<Result<(u64, Vec<las::Point>), String>> = batch
            .par_iter()
            .map(|path| {
                let mut bytes = Vec::new();
                File::open(path).and_then(|mut f| f.read_to_end(&mut bytes)).map_err(|e| e.to_string())?;
                let mut reader = las::Reader::new(std::io::Cursor::new(bytes)).map_err(|e| e.to_string())?;
                let mut kept = Vec::new();
                let mut n = 0u64;
                for pt in reader.points() {
                    let mut pt = pt.map_err(|e| e.to_string())?;
                    n += 1;
                    let (ex, ny) = p.tm.from_merc(pt.x, pt.y);
                    let qx = ((ex - tr.x.offset) / tr.x.scale).round() as i64;
                    let qy = ((ny - tr.y.offset) / tr.y.scale).round() as i64;
                    let qz = ((pt.z - tr.z.offset) / tr.z.scale).round() as i64;
                    if qx < b[0] || qx > b[1] || qy < b[2] || qy > b[3] {
                        continue;
                    }
                    pt.x = qx as f64 * tr.x.scale + tr.x.offset;
                    pt.y = qy as f64 * tr.y.scale + tr.y.offset;
                    pt.z = qz as f64 * tr.z.scale + tr.z.offset;
                    if !fmt.has_color { pt.color = None; }
                    if !fmt.has_nir { pt.nir = None; }
                    if !fmt.has_waveform { pt.waveform = None; }
                    if !fmt.has_gps_time { pt.gps_time = None; } else if pt.gps_time.is_none() { pt.gps_time = Some(0.0); }
                    pt.extra_bytes.clear();
                    kept.push(pt);
                }
                Ok((n, kept))
            })
            .collect();
        for r in decoded {
            let (n, pts) = r?;
            stats.read += n;
            stats.kept += pts.len() as u64;
            for pt in pts {
                writer.write(pt).map_err(|e| e.to_string())?;
            }
        }
    }
    writer.close().map_err(|e| e.to_string())?;
    Ok(stats)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn utm18_matches_pyproj() {
        // pyproj EPSG:3857 -> EPSG:6347 reference.
        let tm = Tm::new(-75.0, 0.9996, 500000.0, 0.0);
        let (e, n) = tm.from_merc(-8346553.0, 5102345.0);
        assert!((e - 501803.04009825486).abs() < 1e-6 && (n - 4606184.223706462).abs() < 1e-6, "{e} {n}");
    }
}
