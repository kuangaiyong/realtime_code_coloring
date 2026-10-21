use std::fmt;

fn larger<T: PartialOrd>(a: T, b: T) -> T {
    if a > b {
        a
    } else {
        b
    }
}

#[derive(Clone, Debug)]
struct Order {
    amount: i64,
}

fn pick<T: Clone>(items: &[T], i: usize) -> T {
    items[i].clone()
}

fn describe<T: fmt::Display>(v: T) -> String {
    format!("<{}>", v)
}

fn chain(v: &[i32]) -> Vec<i32> {
    v.iter().map(|x| x * 2).filter(|x| *x > 2).collect()
}

fn one_liner() -> i32 { let f = || 7; f() }

struct Wrapper<T> {
    v: T,
}

impl<T: fmt::Debug> Wrapper<T> {
    fn show(&self) -> String {
        format!("{:?}", self.v)
    }
}

impl<T: fmt::Debug> fmt::Display for Wrapper<T> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "W({:?})", self.v)
    }
}

trait Greet {
    fn name(&self) -> String;
    fn hi(&self) -> String {
        format!("hi {}", self.name())
    }
}

struct A;
struct B;

impl Greet for A {
    fn name(&self) -> String {
        "a".into()
    }
}

impl Greet for B {
    fn name(&self) -> String {
        "b".into()
    }
}

impl Greet for String {
    fn name(&self) -> String {
        self.clone()
    }
}

trait Tagged {
    fn tag(&self) -> String;
}

impl<T: fmt::Debug> Tagged for T {
    fn tag(&self) -> String {
        format!("#{:?}", self)
    }
}

fn apply_all<T: Copy + fmt::Debug>(items: &[T]) -> Vec<String> {
    items.iter().map(|x| format!("{:?}", x)).collect()
}

fn arr_len<const N: usize>(a: [u8; N]) -> usize {
    a.len() + N
}

fn sum_all(it: impl Iterator<Item = i32>) -> i32 {
    it.sum()
}

fn never_called<T: Clone>(v: &T) -> T {
    v.clone()
}

#[allow(dead_code)]
fn never_referenced<T: Clone>(v: &T) -> T {
    v.clone()
}

fn ident<T>(t: T) -> T {
    t
}

fn plain(x: i32) -> i32 {
    x + 1
}

struct Holder<'a>(&'a str);

fn flag<const B: bool>() -> bool {
    B
}

fn letter<const C: char>() -> char {
    C
}

fn offset<const N: i32>() -> i32 {
    N
}

extern "C" fn c_add(x: i32) -> i32 {
    x + 1
}

fn first_word<'a>(s: &'a str) -> &'a str {
    s.split(' ').next().unwrap_or("")
}

fn main() {
    println!("{}", larger(1i32, 2i32));
    println!("{}", larger(3.5f64, 1.0f64));
    let orders = vec![Order { amount: 5 }];
    println!("{}", pick(&orders, 0).amount);
    println!("{}", pick(&[1u8, 2u8], 1));
    println!("{}", describe("str"));
    println!("{}", describe(String::from("s")));
    println!("{}", describe(42i64));
    println!("{:?}", chain(&[1, 2, 3]));
    println!("{}", one_liner());
    println!("{}", Wrapper { v: 1i32 }.show());
    println!("{}", Wrapper { v: "x" }.show());
    println!("{}", Wrapper { v: 2u8 });
    println!("{}", Wrapper { v: fmt::Error });
    println!("{}", A.hi());
    println!("{}", B.hi());
    println!("{}", String::from("s").hi());
    println!("{}", 5i32.tag());
    println!("{}", Order { amount: 1 }.tag());
    println!("{:?}", apply_all(&[1i32, 2]));
    println!("{:?}", apply_all(&['a', 'b']));
    println!("{}", arr_len([1u8, 2]));
    println!("{}", arr_len([1u8, 2, 3]));
    println!("{}", sum_all(vec![1, 2].into_iter()));
    println!("{}", sum_all((0..3).map(|x| x * 3)));
    println!("{:?}", ident((1i32, 2u8)));
    println!("{:?}", ident([1u8; 4]));
    println!("{:?}", ident(&[1u8, 2][..]));
    let fp: fn(i32) -> i32 = plain;
    println!("{}", ident(fp)(1));
    let d: &dyn fmt::Display = &5;
    println!("{}", ident(d));
    println!("{}", plain(1));
    let it: Box<dyn Iterator<Item = i32>> = Box::new(vec![1, 2].into_iter());
    println!("{}", ident(it).count());
    let x = 5u8;
    println!("{:?}", ident(&x as *const u8));
    let mut y = 6u8;
    println!("{:?}", ident(&mut y as *mut u8));
    let fw: for<'a> fn(&'a str) -> &'a str = first_word;
    println!("{}", ident(fw)("a b"));
    let cf: unsafe extern "C" fn(i32) -> i32 = c_add;
    println!("{}", unsafe { ident(cf)(1) });
    println!("{} {} {}", flag::<true>(), letter::<'x'>(), offset::<-5>());
    println!("{}", ident(Holder("hi")).0);
    if orders.is_empty() {
        println!("{}", never_called(&1u8));
    }
}
