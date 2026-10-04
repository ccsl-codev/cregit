package CregitSrcMl;

# In-process replacement for tokenizeSrcMl.pl: one persistent ctags per language
# and one persistent `srcml2token --server`; only srcml is spawned per file.

use strict;
use warnings;
use bytes;
use Errno qw(EINTR);
use File::Temp ();
use POSIX qw(_exit);

our $PARSER_CRASH_EXIT = 33;

sub new {
    my ($class, %options) = @_;
    my $self = {
        srcml       => $options{srcml}       // "srcml",
        srcml2token => $options{srcml2token} // "srcml2token",
        ctags       => $options{ctags}       // "ctags-universal",
        position    => $options{position}    // 0,
        ctagsProc   => {},
        s2t         => undef,
        child       => undef,
        timedOut    => 0,
    };
    return bless $self, $class;
}

sub tokenize {
    my ($self, $language, $inputName, $workDir, $timeoutSecs) = @_;

    $self->{timedOut} = 0;
    local $SIG{ALRM} = sub {
        $self->{timedOut} = 1;
        $self->kill_all();
    };
    alarm($timeoutSecs) if $timeoutSecs;

    my @result;
    my $ok = eval {
        @result = $self->run($language, $inputName, $workDir);
        1;
    };
    my $error = $@;
    alarm(0);
    my $helperErr = $self->take_helper_stderr();

    if ($self->{timedOut}) {
        $self->shutdown();
        return (124, "", $helperErr . "tokenize command timed out after [$timeoutSecs] seconds\n");
    }
    return (255, "", $helperErr . ($error || "srcml tokenization failed\n")) if not $ok;
    $result[2] = $helperErr . $result[2];
    return @result;
}

sub run {
    my ($self, $language, $inputName, $workDir) = @_;
    my $stderr = "";

    my ($declarations, $listDeclarations) =
        $self->read_declarations($language, $inputName, "$workDir/$inputName");

    my $xmlPath = "$workDir/srcml-output.xml";
    my ($srcmlStatus, $srcmlErr) = $self->run_srcml($language, $inputName, $workDir, $xmlPath);
    $stderr .= $srcmlErr;
    if ($srcmlStatus & 127) {
        my $signal = $srcmlStatus & 127;
        return parser_crash($inputName, $stderr,
            "srcml was killed by signal $signal (shell status " . (128 + $signal) . "). "
            . "srcML 1.1.0 dies on a signal on some C/C++ inputs when --position "
            . "is given; --position cannot be dropped because the token format "
            . "depends on it.");
    }
    if ($srcmlStatus != 0) {
        return parser_crash($inputName, $stderr, "srcml exited " . ($srcmlStatus >> 8) . ".");
    }

    my ($tokenStatus, $tokenLines, $tokenErr) = $self->parse_xml($xmlPath);
    unlink $xmlPath;
    $stderr .= $tokenErr;
    if (not defined $tokenStatus) {
        return parser_crash($inputName, $stderr, "srcml2token died before answering.");
    }
    if ($tokenStatus != 0) {
        return parser_crash($inputName, $stderr, "srcml2token exited $tokenStatus.");
    }
    if (not @$tokenLines) {
        return parser_crash($inputName, $stderr,
            "the token stream was empty. A successful srcML parse always emits at "
            . "least the begin_unit/end_unit wrapper, even for an empty file, so an "
            . "empty stream is a failed parse and never a legitimately empty result.");
    }

    my $output = $self->merge($tokenLines, $declarations, $listDeclarations);
    return (0, $output, $stderr);
}

sub merge {
    my ($self, $tokenLines, $declarations, $listDeclarations) = @_;
    my $position = $self->{position};
    my $lastLine = -1;
    my $output = "";

    for my $tokenLine (@$tokenLines) {
        die "unable to parse srcml line [$tokenLine]"
            unless $tokenLine =~ /^([0-9]+|-):([0-9]+|-)\s+(.+)$/;
        my ($line, $col, $token) = ($1, $2, $3);
        my $lineNum = $line eq '-' ? 0 : $line;
        if ($lineNum != $lastLine) {
            my $names = $listDeclarations->{$line};
            for my $name (@{ $names || [] }) {
                my $decl = $declarations->{$line}{$name};
                die "Illegal value in get declaration [$line][$name]" unless defined $decl;
                $output .= "$line:-|" if $position;
                $output .= "DECL|$decl->{type}|$decl->{name}\n";
            }
        }
        $lastLine = $lineNum;
        $output .= "$line:$col|" if $position;
        $output .= "$token\n";
        if ($token =~ /^end_/) {
            $output .= "-:-|" if $position;
            $output .= "\n";
        }
    }
    return $output;
}

sub parser_crash {
    my ($filename, $stderr, $why) = @_;
    $stderr .= "cregit: tokenization of [$filename] FAILED: $why\n";
    $stderr .= "cregit: refusing to emit a tokenization for [$filename]; "
        . "exiting $PARSER_CRASH_EXIT so the caller can count this blob.\n";
    return ($PARSER_CRASH_EXIT, "", $stderr);
}

sub ctags_for {
    my ($self, $language) = @_;
    my $proc = $self->{ctagsProc}{$language};
    return $proc if $proc;

    $proc = spawn_pipe([ $self->{ctags}, "--_interactive", "--language-force=$language",
                         "--fields=+nKS", "--sort=no" ]);
    my $hello = readline($proc->{out});
    die "ctags did not start: " . ($hello // "EOF") . "\n"
        unless defined $hello and $hello =~ /"_type": "program"/;
    $self->{ctagsProc}{$language} = $proc;
    return $proc;
}

sub read_declarations {
    my ($self, $language, $inputName, $path) = @_;
    my (%declarations, %listDeclarations);

    my $source = do {
        open(my $fh, '<:raw', $path) or die "unable to read [$path]: $!\n";
        local $/;
        <$fh>;
    };
    $source = "" unless defined $source;

    my $proc = $self->ctags_for($language);
    my $in = $proc->{in};
    print {$in} qq({"command":"generate-tags","filename":"$inputName","size":)
        . length($source) . "}\n", $source;
    $in->flush;

    while (1) {
        my $line = readline($proc->{out});
        if (not defined $line) {
            $self->drop_ctags($language);
            last;
        }
        last if $line =~ /"_type": "completed"/;
        if ($line =~ /"_type": "error"/) {
            $self->drop_ctags($language);
            die "ctags rejected the request: $line";
        }
        my $decl = declaration_from_tag($line) or next;
        $declarations{$decl->{line}}{$decl->{name}} = $decl;
        push @{ $listDeclarations{$decl->{line}} }, $decl->{name};
    }
    return (\%declarations, \%listDeclarations);
}

sub declaration_from_tag {
    my ($line) = @_;
    return undef unless $line =~ /"_type": "tag"/;

    my ($n) = $line =~ /"line": ([0-9]+)/;
    my $name = json_field($line, "name");
    my $kind = json_field($line, "kind");
    my $sig  = json_field($line, "signature");
    return undef unless defined $n and defined $name and defined $kind;
    $sig = "-" unless defined $sig;
    for ($name, $kind, $sig) { s/\\/\\\\/g }

    my $xref = "$n $name @@@ $kind @@@ $sig";
    die "unable to parse output [$xref]" unless $xref =~ /^([0-9]+) (.+) @@@ (.*) @@@ (.*)$/;
    my %decl = (line => $1, name => $2, type => $3, sig => $4);
    $decl{name} .= " " . $decl{sig} if $decl{sig} ne "-";
    return \%decl;
}

sub drop_ctags {
    my ($self, $language) = @_;
    $self->retire(delete $self->{ctagsProc}{$language});
}

my %JSON_ESCAPE = (
    '"' => '"', '\\' => '\\', '/' => '/',
    b => "\b", f => "\f", n => "\n", r => "\r", t => "\t",
);

sub json_str {
    my ($s) = @_;
    $s =~ s{\\(["\\/bfnrt]|u[0-9a-fA-F]{4})}{$JSON_ESCAPE{$1} // chr(hex(substr($1, 1)))}ge;
    return $s;
}

sub json_field {
    my ($line, $key) = @_;
    return undef unless $line =~ /"\Q$key\E": "((?:[^"\\]|\\.)*)"/;
    return json_str($1);
}

sub run_srcml {
    my ($self, $language, $inputName, $workDir, $xmlPath) = @_;
    pipe(my $errRead, my $errWrite) or die "unable to create stderr pipe: $!\n";

    my $pid = fork();
    die "unable to fork srcml: $!\n" if not defined $pid;
    if ($pid == 0) {
        close($errRead);
        open(STDIN, '<', '/dev/null');
        open(STDOUT, '>', '/dev/null');
        open(STDERR, '>&', $errWrite) or _exit(255);
        close($errWrite);
        setpgrp(0, 0);
        chdir($workDir) or do { print STDERR "unable to enter [$workDir]: $!\n"; _exit(255) };
        { no warnings 'exec'; exec $self->{srcml}, "-l", $language, "--position", $inputName, "-o", $xmlPath; }
        print STDERR "unable to execute srcml [$self->{srcml}]: $!\n";
        _exit(255);
    }
    close($errWrite);
    $self->{child} = $pid;

    my $stderr = "";
    while (1) {
        my $read = sysread($errRead, $stderr, 65536, length($stderr));
        if (not defined $read) {
            next if $! == EINTR;
            last;
        }
        last if $read == 0;
    }
    close($errRead);
    waitpid($pid, 0);
    my $status = $?;
    $self->{child} = undef;
    return ($status, $stderr);
}

sub s2t {
    my ($self) = @_;
    return $self->{s2t} if $self->{s2t};
    $self->{s2t} = spawn_pipe([ $self->{srcml2token}, "--server" ]);
    return $self->{s2t};
}

sub parse_xml {
    my ($self, $xmlPath) = @_;
    my $proc = $self->s2t();
    my $in = $proc->{in};
    print {$in} "PARSE $xmlPath\n";
    $in->flush;

    my (@tokens, $status);
    my $stderr = "";
    while (1) {
        my $line = readline($proc->{out});
        if (not defined $line) {
            $self->retire(delete $self->{s2t});
            return (undef, \@tokens, $stderr);
        }
        if ($line =~ /\A\x01END ([0-9]+)\n\z/) {
            $status = $1;
            last;
        }
        if ($line =~ /\A\x01ERR (.*)\n\z/s) {
            $stderr .= "$1\n";
            next;
        }
        chomp $line;
        push @tokens, $line;
    }
    return ($status, \@tokens, $stderr);
}

sub spawn_pipe {
    my ($command) = @_;
    pipe(my $childIn, my $parentOut) or die "unable to create pipe: $!\n";
    pipe(my $parentIn, my $childOut) or die "unable to create pipe: $!\n";
    my $err = File::Temp->new(TEMPLATE => "cregit-helper-err-XXXXX", TMPDIR => 1);

    my $pid = fork();
    die "unable to fork [$command->[0]]: $!\n" if not defined $pid;
    if ($pid == 0) {
        close($parentOut);
        close($parentIn);
        open(STDIN,  '<&', $childIn)  or _exit(255);
        open(STDOUT, '>&', $childOut) or _exit(255);
        open(STDERR, '>>', $err->filename) or _exit(255);
        close($childIn);
        close($childOut);
        { no warnings 'exec'; exec @$command; }
        print STDERR "unable to execute [$command->[0]]: $!\n";
        _exit(255);
    }
    close($childIn);
    close($childOut);
    binmode $parentOut;
    binmode $parentIn;
    return { pid => $pid, in => $parentOut, out => $parentIn, err => $err };
}

# A helper's stderr goes to a file, not a pipe: a full pipe would block the
# helper while the worker waits for its stdout.
sub take_helper_stderr {
    my ($self) = @_;
    my @procs = grep { $_ } values(%{ $self->{ctagsProc} }), $self->{s2t};
    my $text = "";
    for my $err (@{ delete $self->{retiredErr} // [] }, map { $_->{err} } @procs) {
        seek($err, 0, 0);
        local $/;
        $text .= <$err> // "";
        truncate($err, 0);
    }
    return $text;
}

sub retire {
    my ($self, $proc) = @_;
    return unless $proc;
    push @{ $self->{retiredErr} }, $proc->{err};
    reap_pipe($proc);
}

sub reap_pipe {
    my ($proc) = @_;
    return unless $proc;
    close($proc->{in}) if defined fileno($proc->{in});
    close($proc->{out}) if defined fileno($proc->{out});
    kill 'TERM', $proc->{pid};
    waitpid($proc->{pid}, 0);
}

sub kill_all {
    my ($self) = @_;
    if (my $pid = $self->{child}) {
        kill 'TERM', -$pid;
        select(undef, undef, undef, 0.1);
        kill 'KILL', -$pid;
    }
    for my $proc (values %{ $self->{ctagsProc} }, $self->{s2t}) {
        kill 'KILL', $proc->{pid} if $proc;
    }
}

sub shutdown {
    my ($self) = @_;
    reap_pipe(delete $self->{ctagsProc}{$_}) for keys %{ $self->{ctagsProc} };
    reap_pipe(delete $self->{s2t});
}

1;
